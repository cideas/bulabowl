package models

import play.api.mvc.{Result, RequestHeader}

import play.api.libs.Crypto
import utils.ZipCreator
import utils.Utils.normalize


/**
 * Created by IntelliJ IDEA.
 * User: Keyston
 * Date: 6/20/12
 * Time: 9:17 PM
 */
case class Signup(username: String, password: String, email: String, name: String)

case class AlbumModel(album: Album)

case class ProfileInfo(username: String, password: String, email: String, name: String)

case class Download(token: String, item: String, kind: String, from: String = "email", sig: String = "") {


  lazy val withItem = Transaction.withItem(token).get

  def query = "from=%s&token=%s&item=%s&kind=%s".format(from, token, item, kind)

  def url(implicit request: RequestHeader) = "http://%s/download?%s".format(
    request.host, query)

  def signedURL(implicit request: RequestHeader) = {
    val u = url(request)
    u + "&sig=" + Crypto.sign(query)
  }

  val canZip = kind.equals("album")

  def valid(implicit request: RequestHeader) = {
    val u = url(request)
    val maybeSig = Crypto.sign(query)
    maybeSig.equals(sig)
  }

  def withZip(artist: Artist, request: RequestHeader) = {
    ZipCreator(artist, this, request).map {
      case (zip, name) => (zip.uri, name, "application/zip")
    }
  }

  def metric = if (kind.equals("album")) AlbumDownload else TrackDownload

  def withMp3 = {
    import utils.Assets.audioStore
    val track = withItem.asInstanceOf[Track]
    Artist.find(track.artistID).map {
      artist =>


        val normalizedArtist = normalize(artist.name, " ")
        val normalizedAlbum = if (track.single) ""
        else AlbumTracks.withAlbum(track.id).map {
          a => normalize(a.name, " ") + " - "
        }.getOrElse("")
        val file = audioStore.full(track.session, track.file.get)

        val uri = "/audio" + file.getAbsolutePath.replace(audioStore.store.getAbsolutePath, "").replace("\\", "/")
        val name = "%s - %s%s.mp3".format(normalizedArtist, normalizedAlbum, track.name)
        (uri, name, "application/octet-stream")

    }


  }

  def withDownload(artist: Artist, creator: (Option[(String, String, String)]) => Result)(implicit request: RequestHeader) = {

    def proxy(info: Option[(String, String, String)]) = {
      Stat(metric, artist.id, withItem.itemID)
      creator(info)
    }
    Either.cond(canZip, withZip(artist, request), withMp3).fold(proxy, proxy)


  }
}