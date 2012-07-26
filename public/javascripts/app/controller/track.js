define(["app/track"], function ()
{

    var Track = require("app/track");

    var Backbone = require("backbone");
    var View = Backbone.View.extend({
        el:"#save",

        events:{
            "click #save-button":"save",
            "click #cancel":"cancel"
        },

        initialize:function ()
        {
            _.bindAll(this);
            this.$saveButton = this.$("#save-button").addClass("disabled");
            var model = this.model = new Track.Model();
            this.model.on("change", this._onAlbumChanged)
            this.trackView = new Track.OverviewView({el:".track-overview", model:model});
            this.editView = new Track.EditView({el:"#track", model:model});

        },
        _onAlbumChanged:function (target, info)
        {
            if ("name" in info.changes) {

                var name = this.model.get("name");
                if (_.isEmpty(name)) {
                    this.$saveButton.addClass("disabled")
                } else {
                    this.$saveButton.removeClass("disabled");
                }
            }


        },
        save:function ()
        {
            this.model.save()
        },
        cancel:function ()
        {

        }
    })

    return {
        View:View
    }


})