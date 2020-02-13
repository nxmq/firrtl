// See LICENSE for license details.

package firrtl.passes
package memlib

import firrtl._
import firrtl.ir._
import firrtl.Utils._
import firrtl.Mappers._
import MemPortUtils.{MemPortMap, Modules}
import MemTransformUtils._
import firrtl.annotations._
import wiring._

import scala.collection.mutable


/** Annotates the name of the pins to add for WiringTransform */
case class PinAnnotation(pins: Seq[String]) extends NoTargetAnnotation

object ReplaceMemMacros {
  class UnsupportedBlackboxMemoryException(msg: String) extends PassException(msg)

  /** Mapping from (module, memory name) pairs to wrapper Module names */
  private type NameMap = mutable.HashMap[(String, String), String]

  /** Mutable datastructure representing mapping of smems to extracted blackboxes and their wrappers
    *
    * - nameMap: mapping from every (module, memory name) to mem blackbox wrapper Module name
    * - bbMap: mapping from wrapper Module name to (blackbox instance, blackbox module)
    * - For bbMap: instance and module name match in the code, but that could be changed
    */
  class MemMapping(val nameMap: NameMap, val bbMap: mutable.HashMap[String, (String, String)])
}

/** Replace DefAnnotatedMemory with memory blackbox + wrapper + conf file.
  * This will not generate wmask ports if not needed.
  * Creates the minimum # of black boxes needed by the design.
  */
class ReplaceMemMacros(writer: ConfWriter) extends Transform {
  def inputForm = MidForm
  def outputForm = MidForm

  import ReplaceMemMacros._

  /** Return true if mask granularity is per bit, false if per byte or unspecified
    */
  private def getFillWMask(mem: DefAnnotatedMemory) = mem.maskGran match {
    case None => false
    case Some(v) => v == 1
  }

  private def rPortToBundle(mem: DefAnnotatedMemory) = BundleType(
    defaultPortSeq(mem) :+ Field("data", Flip, mem.dataType))
  private def rPortToFlattenBundle(mem: DefAnnotatedMemory) = BundleType(
    defaultPortSeq(mem) :+ Field("data", Flip, flattenType(mem.dataType)))

  /** Catch incorrect memory instantiations when there are masked memories with unsupported aggregate types.
    *
    * Example:
    *
    * val memory = SyncReadMem(N, Vec(M, new Bundle {
    *   val a = Bool()
    *   val b = UInt(3.W)
    * }))
    *
    * This memory wrapper will have created M*NUM_BUNDLE_ENTRIES bits or M*2 since createMask matches the
    * structure of the memory datatype. However, the MemConf output will have
    * a maskGran of 4b and thus M mask bits (since M*4b is the total mem. width and (M*4b)/4b = M).
    * Thus, when connecting the blackbox module created from the MemConf file and the FIRRTL wrapper,
    * there will be a mismatch in port size (M != M*2).
    */
  private def checkMaskDatatype(mem: DefAnnotatedMemory) = {
    mem.dataType match {
      case VectorType(at: AggregateType, _) =>
        val msg = s"${mem.info} : Cannot blackbox masked-write memory ${mem.name} with nested aggregate data type."
        throw new ReplaceMemMacros.UnsupportedBlackboxMemoryException(msg)
      case BundleType(_) =>
        val msg = s"${mem.info} : Cannot blackbox masked-write memory ${mem.name} with bundle data type."
        throw new ReplaceMemMacros.UnsupportedBlackboxMemoryException(msg)
      case _ =>
    }
  }

  private def wPortToBundle(mem: DefAnnotatedMemory) = BundleType(
    (defaultPortSeq(mem) :+ Field("data", Default, mem.dataType)) ++ (mem.maskGran match {
      case None => Nil
      case Some(_) => {
        checkMaskDatatype(mem)
        Seq(Field("mask", Default, createMask(mem.dataType)))
      }
    })
  )
  private def wPortToFlattenBundle(mem: DefAnnotatedMemory) = BundleType(
    (defaultPortSeq(mem) :+ Field("data", Default, flattenType(mem.dataType))) ++ (mem.maskGran match {
      case None => Nil
      case Some(_) if getFillWMask(mem) => Seq(Field("mask", Default, flattenType(mem.dataType)))
      case Some(_) => {
        checkMaskDatatype(mem)
        Seq(Field("mask", Default, flattenType(createMask(mem.dataType))))
      }
    })
  )
  // TODO(shunshou): Don't use createMask???

  private def rwPortToBundle(mem: DefAnnotatedMemory) = BundleType(
    defaultPortSeq(mem) ++ Seq(
      Field("wmode", Default, BoolType),
      Field("wdata", Default, mem.dataType),
      Field("rdata", Flip, mem.dataType)
    ) ++ (mem.maskGran match {
      case None => Nil
      case Some(_) => {
        checkMaskDatatype(mem)
        Seq(Field("wmask", Default, createMask(mem.dataType)))
      }
    })
  )
  private def rwPortToFlattenBundle(mem: DefAnnotatedMemory) = BundleType(
    defaultPortSeq(mem) ++ Seq(
      Field("wmode", Default, BoolType),
      Field("wdata", Default, flattenType(mem.dataType)),
      Field("rdata", Flip, flattenType(mem.dataType))
    ) ++ (mem.maskGran match {
      case None => Nil
      case Some(_) if (getFillWMask(mem)) => Seq(Field("wmask", Default, flattenType(mem.dataType)))
      case Some(_) => {
        checkMaskDatatype(mem)
        Seq(Field("wmask", Default, flattenType(createMask(mem.dataType))))
      }
    })
  )

  def memToBundle(s: DefAnnotatedMemory) = BundleType(
    s.readers.map(Field(_, Flip, rPortToBundle(s))) ++
    s.writers.map(Field(_, Flip, wPortToBundle(s))) ++
    s.readwriters.map(Field(_, Flip, rwPortToBundle(s))))
  def memToFlattenBundle(s: DefAnnotatedMemory) = BundleType(
    s.readers.map(Field(_, Flip, rPortToFlattenBundle(s))) ++
    s.writers.map(Field(_, Flip, wPortToFlattenBundle(s))) ++
    s.readwriters.map(Field(_, Flip, rwPortToFlattenBundle(s))))

  /** Creates a wrapper module and external module to replace a candidate memory
   *  The wrapper module has the same type as the memory it replaces
   *  The external module
   */
  def createMemModule(m: DefAnnotatedMemory, wrapperName: String): Seq[DefModule] = {
    assert(m.dataType != UnknownType)
    val wrapperIoType = memToBundle(m)
    val wrapperIoPorts = wrapperIoType.fields map (f => Port(NoInfo, f.name, Input, f.tpe))
    // Creates a type with the write/readwrite masks omitted if necessary
    val bbIoType = memToFlattenBundle(m)
    val bbIoPorts = bbIoType.fields map (f => Port(NoInfo, f.name, Input, f.tpe))
    val bbRef = WRef(m.name, bbIoType)
    val hasMask = m.maskGran.isDefined
    val fillMask = getFillWMask(m)
    def portRef(p: String) = WRef(p, field_type(wrapperIoType, p))
    val stmts = Seq(WDefInstance(NoInfo, m.name, m.name, UnknownType)) ++
      (m.readers flatMap (r => adaptReader(portRef(r), WSubField(bbRef, r)))) ++
      (m.writers flatMap (w => adaptWriter(portRef(w), WSubField(bbRef, w), hasMask, fillMask))) ++
      (m.readwriters flatMap (rw => adaptReadWriter(portRef(rw), WSubField(bbRef, rw), hasMask, fillMask)))
    val wrapper = Module(NoInfo, wrapperName, wrapperIoPorts, Block(stmts))
    val bb = ExtModule(NoInfo, m.name, bbIoPorts, m.name, Seq.empty)
    // TODO: Annotate? -- use actual annotation map

    // add to conf file
    writer.append(m)
    Seq(bb, wrapper)
  }

  // TODO(shunshou): get rid of copy pasta
  // Connects the clk, en, and addr fields from the wrapperPort to the bbPort
  def defaultConnects(wrapperPort: WRef, bbPort: WSubField): Seq[Connect] =
    Seq("clk", "en", "addr") map (f => connectFields(bbPort, f, wrapperPort, f))

  // Generates mask bits (concatenates an aggregate to ground type)
  // depending on mask granularity (# bits = data width / mask granularity)
  def maskBits(mask: WSubField, dataType: Type, fillMask: Boolean): Expression =
    if (fillMask) toBitMask(mask, dataType) else toBits(mask)

  def adaptReader(wrapperPort: WRef, bbPort: WSubField): Seq[Statement]  =
    defaultConnects(wrapperPort, bbPort) :+
    fromBits(WSubField(wrapperPort, "data"), WSubField(bbPort, "data"))

  def adaptWriter(wrapperPort: WRef, bbPort: WSubField, hasMask: Boolean, fillMask: Boolean): Seq[Statement] = {
    val wrapperData = WSubField(wrapperPort, "data")
    val defaultSeq = defaultConnects(wrapperPort, bbPort) :+
      Connect(NoInfo, WSubField(bbPort, "data"), toBits(wrapperData))
    hasMask match {
      case false => defaultSeq
      case true => defaultSeq :+ Connect(
        NoInfo,
        WSubField(bbPort, "mask"),
        maskBits(WSubField(wrapperPort, "mask"), wrapperData.tpe, fillMask)
      )
    }
  }

  def adaptReadWriter(wrapperPort: WRef, bbPort: WSubField, hasMask: Boolean, fillMask: Boolean): Seq[Statement] = {
    val wrapperWData = WSubField(wrapperPort, "wdata")
    val defaultSeq = defaultConnects(wrapperPort, bbPort) ++ Seq(
      fromBits(WSubField(wrapperPort, "rdata"), WSubField(bbPort, "rdata")),
      connectFields(bbPort, "wmode", wrapperPort, "wmode"),
      Connect(NoInfo, WSubField(bbPort, "wdata"), toBits(wrapperWData)))
    hasMask match {
      case false => defaultSeq
      case true => defaultSeq :+ Connect(
        NoInfo,
        WSubField(bbPort, "wmask"),
        maskBits(WSubField(wrapperPort, "wmask"), wrapperWData.tpe, fillMask)
      )
    }
  }

  /** Construct NameMap by assigning unique names for each memory blackbox */
  def constructNameMap(namespace: Namespace, nameMap: NameMap, mname: String)(s: Statement): Statement = {
    s match {
      case m: DefAnnotatedMemory => m.memRef match {
        case None => nameMap(mname -> m.name) = namespace newName m.name
        case Some(_) =>
      }
      case _ =>
    }
    s map constructNameMap(namespace, nameMap, mname)
  }

  // For 1.2.x backwards compatibility
  def updateMemStmts(namespace: Namespace,
                     nameMap: NameMap,
                     mname: String,
                     memPortMap: MemPortMap,
                     memMods: Modules
                    )(s: Statement): Statement =
    updateMemStmts(namespace, nameMap, mname, memPortMap, memMods, None)(s)

  // memMapping is only Option for backwards compatibility
  def updateMemStmts(namespace: Namespace,
                     nameMap: NameMap,
                     mname: String,
                     memPortMap: MemPortMap,
                     memMods: Modules,
                     memMapping: Option[MemMapping]
                    )(s: Statement): Statement = s match {
    case m: DefAnnotatedMemory =>
      if (m.maskGran.isEmpty) {
        m.writers foreach { w => memPortMap(s"${m.name}.$w.mask") = EmptyExpression }
        m.readwriters foreach { w => memPortMap(s"${m.name}.$w.wmask") = EmptyExpression }
      }
      m.memRef match {
        case None =>
          // prototype mem
          val newWrapperName = nameMap(mname -> m.name)
          val newMemBBName = namespace newName s"${newWrapperName}_ext"
          val newMem = m copy (name = newMemBBName)
          // Record for annotation renaming
          memMapping.foreach { mapping =>
            mapping.nameMap += ((mname, m.name) -> newWrapperName)
            mapping.bbMap += newWrapperName -> (newMemBBName, newMemBBName)
          }
          memMods ++= createMemModule(newMem, newWrapperName)
          WDefInstance(m.info, m.name, newWrapperName, UnknownType)
        case Some((module, mem)) =>
          val wrapperName = nameMap(module -> mem)
          // Record for annotation renaming
          memMapping.foreach(_.nameMap += ((mname, m.name) -> wrapperName))
          WDefInstance(m.info, m.name, wrapperName, UnknownType)
      }
    case sx => sx map updateMemStmts(namespace, nameMap, mname, memPortMap, memMods, memMapping)
  }


  // For 1.2.x backwards compatibility
  def updateMemMods(namespace: Namespace,
                    nameMap: NameMap,
                    memMods: Modules
                   )(m: DefModule): DefModule =
    updateMemMods(namespace, nameMap, memMods, None)(m)

  // memMapping is only Option for backwards compatibility
  def updateMemMods(namespace: Namespace,
                    nameMap: NameMap,
                    memMods: Modules,
                    memMapping: Option[MemMapping]
                   )(m: DefModule): DefModule = {
    val memPortMap = new MemPortMap

    (m map updateMemStmts(namespace, nameMap, m.name, memPortMap, memMods, memMapping)
       map updateStmtRefs(memPortMap))
  }

  def execute(state: CircuitState): CircuitState = {
    val c = state.circuit
    val namespace = Namespace(c)
    val memMods = new Modules
    val nameMap = new NameMap
    c.modules map (m => m map constructNameMap(namespace, nameMap, m.name))
    val memMapping = new MemMapping(new NameMap, new mutable.HashMap)
    val modules = c.modules map updateMemMods(namespace, nameMap, memMods, Some(memMapping))

    // Rename replaced memories with new blackbox
    val renames = RenameMap.create {
      val top = CircuitTarget(c.main)
      memMapping.nameMap.map { case ((mod, mem), wrap) =>
        val (bbInst, bbMod) = memMapping.bbMap(wrap)
        top.module(mod).ref(mem) -> Seq(top.module(mod).instOf(mem, wrap).instOf(bbInst, bbMod))
      }
    }

    // print conf
    writer.serialize()
    val pannos = state.annotations.collect { case a: PinAnnotation => a }
    val pins = pannos match {
      case Seq() => Nil
      case Seq(PinAnnotation(pins)) => pins
      case _ => throwInternalError(s"execute: getMyAnnotations - ${getMyAnnotations(state)}")
    }
    val annos = pins.foldLeft(Seq[Annotation]()) { (seq, pin) =>
      seq ++ memMods.collect {
        case m: ExtModule => SinkAnnotation(ModuleName(m.name, CircuitName(c.main)), pin)
      }
    } ++ state.annotations
    CircuitState(c.copy(modules = modules ++ memMods), inputForm, annos, renames = Some(renames))
  }
}
