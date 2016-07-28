package models.billing

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._

import cache._
import db._
import models.Constants._
import io.megam.auth.funnel.FunnelErrors._

import com.datastax.driver.core.{ ResultSet, Row }
import com.websudos.phantom.dsl._
import scala.concurrent.{ Future => ScalaFuture }
import com.websudos.phantom.connectors.{ ContactPoint, KeySpaceDef }
import scala.concurrent.Await
import scala.concurrent.duration._

import io.megam.util.Time
import app.MConfig
import io.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset

/**
 * @author ranjitha
 *
 */

case class SubscriptionsInput( model: String, license: String, trial_ends: String) {

}

case class SubscriptionsResult(
    id: String,
    account_id: String,
    model: String,
    license: String,
    trial_ends: String,
    json_claz: String,
    created_at: String) {
}

sealed class SubscriptionsSacks extends CassandraTable[SubscriptionsSacks, SubscriptionsResult] {

  implicit val formats = DefaultFormats

  object id extends StringColumn(this) with  PartitionKey[String]
  object account_id extends StringColumn(this) with PrimaryKey[String]
  object model extends StringColumn(this)
  object license extends StringColumn(this)
  object trial_ends extends StringColumn(this)
  object json_claz extends StringColumn(this)
  object created_at extends StringColumn(this)

  def fromRow(row: Row): SubscriptionsResult = {
    SubscriptionsResult(
      id(row),
      account_id(row),
      model(row),
      license(row),
      trial_ends(row),
      json_claz(row),
      created_at(row))
  }
}

abstract class ConcreteSubscriptions extends SubscriptionsSacks with RootConnector {
  // you can even rename the table in the schema to whatever you like.
  override lazy val tableName = "subscriptions"
  override implicit def space: KeySpace = scyllaConnection.space
  override implicit def session: Session = scyllaConnection.session

  def insertNewRecord(ams: SubscriptionsResult): ValidationNel[Throwable, ResultSet] = {
    val res = insert.value(_.id, ams.id)
      .value(_.account_id, ams.account_id)
      .value(_.model, ams.model)
      .value(_.license, ams.license)
      .value(_.trial_ends, ams.trial_ends)
      .value(_.json_claz, ams.json_claz)
      .value(_.created_at, ams.created_at)
      .future()
    Await.result(res, 5.seconds).successNel
  }

  def getRecords(email: String): ValidationNel[Throwable, Seq[SubscriptionsResult]] = {
    val res = select.allowFiltering().where(_.account_id eqs email).fetch()
    Await.result(res, 5.seconds).successNel
  }

}

object Subscriptions extends ConcreteSubscriptions {

  /**
   * A private method which chains computation to make GunnySack when provided with an input json, email.
   * parses the json, and converts it to eventsinput, if there is an error during parsing, a MalformedBodyError is sent back.
   * After that flatMap on its success and the account id information is looked up.
   * If the account id is looked up successfully, then yield the GunnySack object.
   */
  private def mkSubscriptionsSack(email: String, input: String): ValidationNel[Throwable, SubscriptionsResult] = {
    val subInput: ValidationNel[Throwable, SubscriptionsInput] = (Validation.fromTryCatchThrowable[SubscriptionsInput, Throwable] {
      parse(input).extract[SubscriptionsInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel //capture failure

    for {
      sub <- subInput
      uir <- (UID("sub").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {

      val bvalue = Set(email)
      val json = new SubscriptionsResult(uir.get._1 + uir.get._2, email, sub.model, sub.license, sub.trial_ends, "Megam::Subscriptions", Time.now.toString)
      json
    }
  }

  /*
   * create new subscriptions for user.
   *
   */
  def create(email: String, input: String): ValidationNel[Throwable, Option[SubscriptionsResult]] = {
    for {
      wa <- (mkSubscriptionsSack(email, input) leftMap { err: NonEmptyList[Throwable] => err })
      set <- (insertNewRecord(wa) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      play.api.Logger.warn(("%s%s%-20s%s").format(Console.GREEN, Console.BOLD, "Subscriptions.created success", Console.RESET))
      wa.some
    }
  }

  def findById(email: String): ValidationNel[Throwable, Seq[SubscriptionsResult]] = {
    (getRecords(email) leftMap { t: NonEmptyList[Throwable] =>
      new ResourceItemNotFound(email, "Subscriptions = nothing found.")
    }).toValidationNel.flatMap { nm: Seq[SubscriptionsResult] =>
      if (!nm.isEmpty)
        Validation.success[Throwable, Seq[SubscriptionsResult]](nm).toValidationNel
      else
        Validation.failure[Throwable, Seq[SubscriptionsResult]](new ResourceItemNotFound(email, "Subscriptions = nothing found.")).toValidationNel
    }

  }

}
