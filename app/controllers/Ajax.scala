package controllers

import play.api._
import play.api.mvc._
import models._
import models.Forms._
import com.codahale.jerkson.Json._
import jp.t2v.lab.play20.auth.Auth
import org.squeryl.PrimitiveTypeMode
import actions.SquerylTransaction
import java.io.{File, FileFilter}
import scala.Some
import models.AlbumTracks
import services.PayPal


/**
 * Created by IntelliJ IDEA.
 * User: Keyston
 * Date: 6/11/12
 * Time: 11:18 AM
 */

object Ajax extends Controller with Auth with AuthConfigImpl with WithDB with SquerylTransaction {

  import models.SiteDB._
  import PrimitiveTypeMode._






  def tags(query: String) = TransAction {
    Action {
      val found =
        Tag.search(query).map {
          case (t) => Map("name" -> t.name)
        }



      Ok(generate(found))
    }
  }


  def saveTrack() = authorizedAction(NormalUser) {
    implicit artist => implicit request =>
      singleTrackForm.bindFromRequest.fold(
        errors => BadRequest(""),
        track => {

          Ok("") //generate(track.save()))
        }
      )
  }

  def fetchTrack(id: Long) = TransAction {
    authorizedAction(NormalUser) {
      artist => implicit request =>

        Ok(Track.find(id).map {
          case t => if (t.artistID == artist.id) generate(t) else ""
        }.getOrElse(""))


    }
  }

  def publish(kind: String, slug: String) = TransAction {
    authorizedAction(NormalUser) {
      implicit artist => implicit request =>

        kind match {
          case "album" => Album.bySlug(artist.id, slug).map {
            album =>
              update(albums)(a =>
                where(a.id === album.id)
                  set (a.active := true))

              update(tracks)(t =>
                where(t.id in
                  from(Album.withTracks(album.id))(t2 => select(t2.id))
                )
                  set (t.active := true)
              )


          }
          case "track" =>
        }
        Ok("")

    }
  }

  def fetchAlbum(slug: String) = TransAction {
    authorizedAction(NormalUser) {
      artist => implicit request =>
        Ok(Album.bySlug(artist.id, slug).map {
          a => generate(Map("album" -> a, "tracks" -> Album.withTracks(a.id).toList))
        }.getOrElse(""))

    }
  }

  private def g(obj: Any) = generate(obj)

  private def json(obj: Any) = Ok(g(obj)).as("text/json")

  private def error(obj: Any) = BadRequest(g(obj)).as("text/json")

  def deleteAlbum(slug: String) = TransAction {
    authorizedAction(NormalUser) {
      artist => implicit request =>

        Album.bySlug(artist.id, slug).map {
          album =>
            import utils.AudioDataStore.deleteAudioSession
            deleteAudioSession(album.session)
            albums.delete(album.id)
            val allTracks = from(albumTracks)(at =>
              where(at.albumID === album.id)
                select (at)

            )
            tracks.deleteWhere(t =>
              (t.id in from(allTracks)(at => select(at.trackID)))

            )
            albumTracks.delete(allTracks)
            json(Map("ok" -> true))

        }.getOrElse(error(Map("ok" -> false)))
    }
  }

  def updateAlbum(slug: String) = TransAction {
    authorizedAction(NormalUser) {
      implicit artist => implicit request =>

        commitAlbum(artist)
    }
  }

  def saveAlbum() = TransAction {
    authorizedAction(NormalUser) {
      implicit artist => implicit request =>

        commitAlbum(artist)


    }
  }

  private def commitAlbum(artist: Artist)(implicit request: play.api.mvc.Request[_]): play.api.mvc.Result = {

    albumForm.bindFromRequest.fold(
      errors => BadRequest(errors.errorsAsJson),
      value => {

        import utils.TempImage.commitImagesForSession
        import utils.TempAudioDataStore.commitAudioForSession
        import utils.Assets.tempAudioStore
        import utils.ffmpeg

        var (album, allTracks) = value

        val albumSlugs = from(albums)(a =>
          where(a.artistID === artist.id)
            select (a.slug)
        ).toList






        val albumSlug = album.slug
        var counter = 2
        while (albumSlugs.contains(album.slug)) {
          album.slug = albumSlug + "-" + counter
          counter += 1
        }

        // update or delete album
        if (album.id == 0) album.save else album.update

        // select all slugs
        var slugs = from(tracks)(t =>
          where(t.artistID === artist.id)
            select (t.slug)
        ).toList

        allTracks.foreach {
          t =>
            var counter = 2
            val slug = t.slug

            while (slugs.contains(t.slug)) {
              t.slug = slug + "-" + counter
              counter += 1
            }

            // update active slug list just in case a user inputs the same title twice in
            // the same update session

            if (slug != t.slug) slugs ++= List(t.slug)
        }


        // update the tracks
        tracks.update(allTracks.filter(_.id != 0))



        // save all the new tracks
        allTracks.foreach(t => if (t.id == 0) t.save)



        // all tracks have their id now, so create a map of all ideas
        val currentTrackIds = allTracks.map(_.id)

        // create a list of active hashs from the art and file attribute,
        // this will allow us to only copy files that are being saved,
        // so if the user deletes a file before a save we don't worry about saving that one,
        // this will prevent any false overwritting of files
        val activeHashes = List(album.art.getOrElse("")) ++ allTracks.map(_.file.getOrElse("")) ++ allTracks.map(_.art.getOrElse(""))

        // go ahead and delete files that were not send over with this save
        // TODO: maybe this should clean up on the delete files
        tracks.deleteWhere(t => t.id notIn currentTrackIds and t.artistID === artist.id)



        // delete all the album order information and reinsert
        albumTracks.delete(albumTracks.where(at => at.albumID === album.id))
        var order = 0

        albumTracks.insert(allTracks.map(t => {
          order += 1
          AlbumTracks(album.id, t.id, order)
        }))

        // compose our filter that checks for active file hashes(art and mp3) for the giving session
        val commitFileFilter = new FileFilter() {
          def accept(file: File) = {

            val answer = file.getName.split("_").headOption.map(f => activeHashes.contains(f)).getOrElse(false)
            answer
          }
        }
        commitImagesForSession(album.session, Some(commitFileFilter))
        commitAudioForSession(album.session, Some(commitFileFilter))





        Ok(generate(Map("album" -> album, "tracks" -> allTracks)))

      }
    )
  }
}



