package models

import java.text.SimpleDateFormat
import models.SiteDB._

import org.squeryl.PrimitiveTypeMode._
import scala.Some
import java.sql.{Date, Timestamp}
import org.squeryl.KeyedEntity
import services.PaypalAdaptive
import org.joda.time.{DateTimeZone, DateTime}


/**
 * Created by IntelliJ IDEA.
 * User: Keyston
 * Date: 7/26/12
 * Time: 12:22 AM
 */
case class Transaction(sig: String, itemID: Long, amount: Double, kind: String, token: String, status: String = "pending", correlationID: Option[String] = Some(""), payerID: Option[String] = Some(""),
                       transactionID: Option[String] = Some(""), ack: String = "",
                       created: Option[Timestamp] = None) extends KeyedEntity[Long] {
  var id: Long = 0


  def this() = this("", 0, 0, "", "", "pending", Some(""), Some(""), Some(""), "", Some(new Timestamp(DateTime.now(DateTimeZone.UTC).getMillis)))

  import models.Transaction.PURCHASE_TRACK

  def metric = if (kind == PURCHASE_TRACK) PurchaseTrack else PurchaseAlbum
}

object Transaction {
  val PURCHASE_TRACK = "track"
  val PURCHASE_ALBUM = "album"
  val STATUS_PENDING = "pending"
  val STATUS_CALLBACK = "callback"
  val STATUS_ERROR = "error"
  val STATUS_VOID = "void"
  val STATUS_COMPLETED = "completed"
  val STATUS_CHECKOUT = "checkout"

  lazy val timeFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")


  def fromTimestamp(date: String) = timeFormatter format date

  def withItem(token: String) = {
    byToken(token).map {
      t =>
        val item = if (t.kind == PURCHASE_ALBUM)
          albums.where(a => a.id === t.itemID).head
        else tracks.where(t => t.id === t.itemID).head

        Some(item)

    }.getOrElse(None)
  }

  def withArtistAndItem(token: String) = {
    byToken(token).map {
      t =>
        val item = if (t.kind == PURCHASE_ALBUM)
          albums.where(a => a.id === t.itemID).head
        else tracks.where(t => t.id === t.itemID).head

        Some((t, artists.where(a => a.id === item.ownerID).head, item))

    }.getOrElse(None)

  }

  def withArtist(token: String): Option[Artist] =
    withArtistAndItem(token).map {
      case (_, artist, _) => artist
    }


  def byToken(token: String) = transactions.where(t => t.token === token).headOption

  def bySig(sig: String) = transactions.where(t => t.sig === sig).headOption

  def status(token: String, status: String) = {
    update(transactions)(t =>
      where(t.token === token)
        set (t.status := status)
    )
  }

  def commit(token: String, correlationID: Option[String], transactionID: Option[String]) = {
    update(transactions)(t =>
      where(t.token === token)
        set(t.status := STATUS_COMPLETED,
        t.correlationID := correlationID,
        t.transactionID := transactionID)
    )
  }

  def apply(sig: String, item: SaleAbleItem, amount: Double, token: String) = inTransaction {
    Some(transactions.insert(new Transaction(sig, item.itemID, amount, item.itemType, token)))
  }


}

case class Sale(transactionID: Long, downloads: Int, amount: Double, percentage: Double, createdAt: Timestamp) extends KeyedEntity[Long] {
  val id: Long = 0

  def this() = this(0, 0, 0, 0, new Timestamp(DateTime.now(DateTimeZone.UTC).getMillis))
}

object Sale {

  import Transaction.withArtist

  lazy val paypal = new PaypalAdaptive()
  lazy val percentage = paypal.percentage


  def apply(transaction: Transaction) = {
    try {
      val artist = withArtist(transaction.token).get

      Stat(transaction.metric, artist.id, transaction.itemID)
      Some(new Sale(transaction.id, 0,
        transaction.amount, percentage,
        new Timestamp(DateTime.now(DateTimeZone.UTC).getMillis)).save)


    } catch {
      case e: Exception => None
    }

  }
}

