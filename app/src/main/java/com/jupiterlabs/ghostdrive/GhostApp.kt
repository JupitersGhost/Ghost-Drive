package com.jupiterlabs.ghostdrive

import android.app.Application
import com.jupiterlabs.ghostdrive.data.Repository
import com.jupiterlabs.ghostdrive.data.DemoController
import com.jupiterlabs.ghostdrive.sync.PrivateSync
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class GhostApp : Application() {
    lateinit var repository: Repository; private set
    lateinit var demo: DemoController; private set
    lateinit var sync: PrivateSync; private set
    override fun onCreate() { super.onCreate(); AndroidGraphicFactory.createInstance(this); repository = Repository(this); demo = DemoController(repository); sync = PrivateSync(repository) }
}
