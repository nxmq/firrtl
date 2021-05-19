// SPDX-License-Identifier: Apache-2.0

package firrtl
package passes
package memlib

import firrtl.Utils.error
import firrtl.stage.Forms

class CreateMemoryAnnotations extends Transform with DependencyAPIMigration {

  override def prerequisites = Forms.MidForm
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Forms.MidEmitters
  override def invalidates(a: Transform) = false

  def execute(state: CircuitState): CircuitState = {
    state.copy(annotations = state.annotations.flatMap {
      case ReplSeqMemAnnotation(inputFileName, outputConfig) =>
        Seq(MemLibOutConfigFileAnnotation(outputConfig, Nil)) ++ {
          if (inputFileName.isEmpty) None
          // @todo remove java.io.File
          else if (new java.io.File(inputFileName).exists) {
            import CustomYAMLProtocol._ // implicit parameter for ↓
            Some(PinAnnotation(new YamlFileReader(inputFileName).parse[Config].map(_.pin.name)))
          } else error("Input configuration file does not exist!")
        }
      case a => Seq(a)
    })
  }
}
