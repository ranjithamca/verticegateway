/*
** Copyright [2013-2015] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package models.tosca

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._

import cache._
import db._
import models.json.tosca._
import models.json.tosca.box._
import controllers.Constants._
import controllers.funnel.FunnelErrors._
import app.MConfig
import models.base._

import org.megam.util.Time
import com.stackmob.scaliak._
import com.basho.riak.client.core.query.indexes.{ RiakIndexes, StringBinIndex, LongIntIndex }
import com.basho.riak.client.core.util.{ Constants => RiakConstants }
import org.megam.common.riak.GunnySack

import org.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset

/**
 * @author rajthilak
 *
 */

// The component inputs field have following fields
//    1.domain
//    2.port
//    3.username
//    4.password
//    5.version
//    6.version
//    7.source
//    8.id
//    10.x
//    11.y
//    12.z
//    13.wires
//    14.dbname
//    15.dbpassword
// These fields are presents at inputs array for APP or SERVICE components

case class Artifacts(artifact_type: String, content: String, requirements: KeyValueList) {
  val json = "{\"artifact_type\":\"" + artifact_type + "\",\"content\":\"" + content + "\",\"requirements\":" + KeyValueList.toJson(requirements, true) + "}"
}

object Artifacts {
  def empty: Artifacts = new Artifacts(new String(), new String(), KeyValueList.empty)
}

case class Repo(rtype: String, source: String, oneclick: String, url: String) {
  val json = "{\"rtype\":\"" + rtype + "\",\"source\":\"" + source + "\",\"oneclick\":\"" + oneclick + "\",\"url\":\"" + url + "\"}"
}

object Repo {
  def empty: Repo = new Repo(new String(), new String(), new String(), new String())
}

case class ComponentResult(id: String, name: String, tosca_type: String, inputs: models.tosca.KeyValueList, outputs: models.tosca.KeyValueList, envs: models.tosca.KeyValueList, artifacts: Artifacts, related_components: models.tosca.BindLinks, operations: models.tosca.OperationList, status: String, repo: Repo, created_at: String) {
  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    val preser = new ComponentResultSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    prettyRender(toJValue)
  } else {
    compactRender(toJValue)
  }
}

object ComponentResult {

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[ComponentResult] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    val preser = new ComponentResultSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[ComponentResult] = (Validation.fromTryCatchThrowable[net.liftweb.json.JValue, Throwable] {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}

case class ComponentUpdateInput(id: String, name: String, tosca_type: String, inputs: models.tosca.KeyValueList, outputs: models.tosca.KeyValueList, envs: models.tosca.KeyValueList, artifacts: Artifacts, related_components: models.tosca.BindLinks, operations: models.tosca.OperationList, status: String, repo: Repo) {
  val json = "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"tosca_type\":\"" + tosca_type + "\",\"inputs\":" + models.tosca.KeyValueList.toJson(inputs, true) + ",\"outputs\":" + models.tosca.KeyValueList.toJson(outputs, true) + ",\"envs\":" + models.tosca.KeyValueList.toJson(envs, true) + ",\"artifacts\":" + artifacts.json +
    ",\"related_components\":" + models.tosca.BindLinks.toJson(related_components, true) + ",\"operations\":" + models.tosca.OperationList.toJson(operations, true) + ",\"status\":\"" + status + "\",\"repo\":" + repo.json + "}"
}

case class Component(name: String, tosca_type: String, inputs: models.tosca.KeyValueList, outputs: models.tosca.KeyValueList, envs: models.tosca.KeyValueList, artifacts: Artifacts, related_components: models.tosca.BindLinks, operations: models.tosca.OperationList, repo: Repo, status: String) {
  val json = "{\"name\":\"" + name + "\",\"tosca_type\":\"" + tosca_type + "\",\"inputs\":" + models.tosca.KeyValueList.toJson(inputs, true) + ",\"outputs\":" + models.tosca.KeyValueList.toJson(outputs, true) + ",\"envs\":" + models.tosca.KeyValueList.toJson(envs, true) + ",\"artifacts\":" + artifacts.json +
    ",\"related_components\":" + models.tosca.BindLinks.toJson(related_components, true) + ",\"operations\":" + models.tosca.OperationList.toJson(operations, true) + ",\"status\":\"" + status + "\", \"repo\":" + repo.json + "}"

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    val preser = new models.json.tosca.box.ComponentSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    prettyRender(toJValue)
  } else {
    compactRender(toJValue)
  }
}

object Component {
  implicit val formats = DefaultFormats
  private val riak = GWRiak("components")

  val metadataKey = "Component"
  val metadataVal = "Component Creation"
  val bindex = "component"

  def empty: Component = new Component(new String(), new String(), KeyValueList.empty, KeyValueList.empty, KeyValueList.empty, Artifacts.empty, BindLinks.empty, OperationList.empty, Repo.empty, new String())

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[Component] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    val preser = new ComponentSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[Component] = (Validation.fromTryCatchThrowable[net.liftweb.json.JValue, Throwable] {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

  def findById(componentID: Option[List[String]]): ValidationNel[Throwable, ComponentsResults] = {
    (componentID map {
      _.map { asm_id =>
        play.api.Logger.debug(("%-20s -->[%s]").format("Asm Id", asm_id))
        (riak.fetch(asm_id) leftMap { t: NonEmptyList[Throwable] =>
          new ServiceUnavailableError(asm_id, (t.list.map(m => m.getMessage)).mkString("\n"))
        }).toValidationNel.flatMap { xso: Option[GunnySack] =>
          xso match {
            case Some(xs) => {
              (Validation.fromTryCatchThrowable[ComponentResult, Throwable] {
                parse(xs.value).extract[ComponentResult]
              } leftMap { t: Throwable => new MalformedBodyError(xs.value, t.getMessage) }).toValidationNel.flatMap { j: ComponentResult =>
                play.api.Logger.debug(("%-20s -->[%s]").format("Component result", j))
                Validation.success[Throwable, ComponentsResults](nels(j.some)).toValidationNel //screwy kishore, every element in a list ?
              }
            }
            case None => {
              Validation.failure[Throwable, ComponentsResults](new ResourceItemNotFound(asm_id, "")).toValidationNel
            }
          }
        }
      } // -> VNel -> fold by using an accumulator or successNel of empty. +++ => VNel1 + VNel2
    } map {
      _.foldRight((ComponentsResults.empty).successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head.
  }

  private def updateGunnySack(email: String, input: String): ValidationNel[Throwable, Option[GunnySack]] = {
    val ripNel: ValidationNel[Throwable, ComponentUpdateInput] = (Validation.fromTryCatchThrowable[ComponentUpdateInput, Throwable] {
      parse(input).extract[ComponentUpdateInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel //capture failure

    for {
      rip <- ripNel
      aor <- (Accounts.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t })
      com_collection <- (Component.findById(List(rip.id).some) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      val bvalue = Set(aor.get.id)
      val com = com_collection.head
      val json = ComponentResult(rip.id, com.get.name, com.get.tosca_type, com.get.inputs ::: rip.inputs, com.get.outputs ::: rip.outputs, com.get.envs ::: rip.envs, com.get.artifacts, com.get.related_components ::: rip.related_components, com.get.operations ::: rip.operations, com.get.status, com.get.repo, com.get.created_at).toJson(false)
      new GunnySack((rip.id), json, RiakConstants.CTYPE_TEXT_UTF8, None,
        Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
    }
  }

  def update(email: String, input: String): ValidationNel[Throwable, ComponentsResults] = {
    for {
      gs <- (updateGunnySack(email, input) leftMap { err: NonEmptyList[Throwable] => err })
      maybeGS <- (riak.store(gs.get) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      val nrip = parse(gs.get.value).extract[ComponentResult]
      maybeGS match {
        case Some(thatGS) =>
          nels(ComponentResult(thatGS.key, nrip.name, nrip.tosca_type, nrip.inputs, nrip.outputs, nrip.envs, nrip.artifacts, nrip.related_components, nrip.operations, nrip.status, nrip.repo, Time.now.toString()).some)
        case None => {
          play.api.Logger.warn(("%s%s%-20s%s").format(Console.GREEN, Console.BOLD, "Component.updated successfully", Console.RESET))
          nels(ComponentResult(nrip.id, nrip.name, nrip.tosca_type, nrip.inputs, nrip.outputs, nrip.envs, nrip.artifacts, nrip.related_components, nrip.operations, nrip.status, nrip.repo, Time.now.toString()).some)

        }
      }
    }
  } 

}

object ComponentsList {

  implicit val formats = DefaultFormats

  implicit def ComponentsResultsSemigroup: Semigroup[ComponentsResults] = Semigroup.instance((f1, f2) => f1.append(f2))

  val emptyRR = List(Component.empty)
  def toJValue(nres: ComponentsList): JValue = {

    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.tosca.carton.ComponentsListSerialization.{ writer => ComponentsListWriter }
    toJSON(nres)(ComponentsListWriter)
  }

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[ComponentsList] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    import models.json.tosca.carton.ComponentsListSerialization.{ reader => ComponentsListReader }
    fromJSON(jValue)(ComponentsListReader)
  }

  def toJson(nres: ComponentsList, prettyPrint: Boolean = false): String = if (prettyPrint) {
    prettyRender(toJValue(nres))
  } else {
    compactRender(toJValue(nres))
  }

  def apply(componentList: List[Component]): ComponentsList = { componentList }

  def empty: List[Component] = emptyRR

  private val riak = GWRiak("components")

  val metadataKey = "COMPONENT"
  val metadataVal = "Component Creation"
  val bindex = "component"

  /**
   * A private method which chains computation to make GunnySack when provided with an input json, email.
   * parses the json, and converts it to nodeinput, if there is an error during parsing, a MalformedBodyError is sent back.
   * After that flatMap on its success and the account id information is looked up.
   * If the account id is looked up successfully, then yield the GunnySack object.
   */
  private def mkGunnySack(authBag: Option[controllers.stack.AuthBag], input: Component, asm_id: String): ValidationNel[Throwable, Option[GunnySack]] = {
    for {
      aor <- (Accounts.findByEmail(authBag.get.email) leftMap { t: NonEmptyList[Throwable] => t })
      uir <- (UID(MConfig.snowflakeHost, MConfig.snowflakePort, "com").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {
      import models.tosca.KeyValueList._
      val bvalue = Set(aor.get.id)
      val json = "{\"id\": \"" + (uir.get._1 + uir.get._2) + "\",\"name\":\"" + input.name +
        "\",\"tosca_type\":\"" + input.tosca_type + "\",\"inputs\":" + KeyValueList.toJson(input.inputs, true) + ",\"outputs\":" + KeyValueList.toJson(input.outputs, true) +
        ",\"envs\":" + KeyValueList.toJson(input.envs, true,
          Map(MKT_FLAG_EMAIL -> authBag.get.email,
            MKT_FLAG_APIKEY -> authBag.get.api_key,
            MKT_FLAG_ASSEMBLY_ID -> asm_id,
            MKT_FLAG_COMP_ID -> (uir.get._1 + uir.get._2),
            MKT_FLAG_SPARKJOBSERVER -> app.MConfig.spark_jobserver)) +
          ",\"artifacts\":" + input.artifacts.json + ",\"related_components\":" + BindLinks.toJson(input.related_components, true) +
          ",\"operations\":" + OperationList.toJson(input.operations, true) + ",\"status\":\"" + input.status +
          "\",\"repo\":" + input.repo.json + ",\"created_at\":\"" + Time.now.toString + "\"}"

      new GunnySack((uir.get._1 + uir.get._2), json, RiakConstants.CTYPE_TEXT_UTF8, None,
        Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
    }
  }

  def createLinks(authBag: Option[controllers.stack.AuthBag], input: ComponentsList, asm_id: String): ValidationNel[Throwable, ComponentsResults] = {
    var res = (ComponentsResults.empty).successNel[Throwable]
    if (input.isEmpty) {
      res = (ComponentsResults.empty).successNel[Throwable]
    } else {
      res = (input map { asminp => (create(authBag, asminp, asm_id))
      }).foldRight((ComponentsResults.empty).successNel[Throwable])(_ +++ _)
    }
    play.api.Logger.debug(("%-20s -->[%s]").format("models.tosca.Components", res))
    res
  }

  /*
   * create new market place item with the 'name' of the item provide as input.
   * A index name assemblies name will point to the "csars" bucket
   */
  def create(authBag: Option[controllers.stack.AuthBag], input: Component, asm_id: String): ValidationNel[Throwable, ComponentsResults] = {
    (mkGunnySack(authBag, input, asm_id) leftMap { err: NonEmptyList[Throwable] =>
      new ServiceUnavailableError(input.name, (err.list.map(m => m.getMessage)).mkString("\n"))
    }).toValidationNel.flatMap { gs: Option[GunnySack] =>

      (riak.store(gs.get) leftMap { t: NonEmptyList[Throwable] => t }).
        flatMap { maybeGS: Option[GunnySack] =>
          maybeGS match {
            case Some(thatGS) => nels((parse(thatGS.value).extract[ComponentResult]).some).successNel[Throwable]
            case None => {
              play.api.Logger.warn(("%s%s%-20s%s").format(Console.GREEN, Console.BOLD, "Components.created success", Console.RESET))
              nels((parse(gs.get.value).extract[ComponentResult]).some).successNel[Throwable];
            }
          }
        }
    }

  }
}
