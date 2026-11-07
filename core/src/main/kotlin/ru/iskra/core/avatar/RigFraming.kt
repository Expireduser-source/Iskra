package ru.iskra.core.avatar

/**
 * Where the rig goes on the app's own screens (0.16.0): the whole figure standing in an area (the welcome, the
 * introduction), or a portrait — her head in a circle (the chat header, the faces beside her messages).
 * A [Placement] is the size of the rig's box ([RigDrawer.draw]) and where its top-left corner goes.
 */
object RigFraming {
    /** The centre of a portrait and the radius around it, in model pixels: ear tips to chin, a little of the shoulders. */
    const val FACE_X = 612f
    const val FACE_Y = 420f
    const val FACE_R = 410f

    /** Her chest, where the spark of her birth shines, in model pixels. */
    private const val HEART_Y = 850f

    data class Placement(val box: Float, val left: Float, val top: Float)

    /** The whole figure on the bottom of a [w]×[h] area, centred. */
    fun full(w: Float, h: Float): Placement {
        val box = minOf(w, h)
        return Placement(box, (w - box) / 2f, h - box)
    }

    /** Her head filling a circle as wide as the shorter side of a [w]×[h] area, centred. */
    fun portrait(w: Float, h: Float): Placement {
        val d = minOf(w, h)
        val box = d / 2f / FACE_R * (RigDrawer.FEET - RigDrawer.TOP) / SpriteLayout.FILL
        val k = RigDrawer.scale(box)
        val x = box / 2f + (FACE_X - RigDrawer.ANCHOR_X) * k
        val y = box * SpriteLayout.BASELINE + (FACE_Y - RigDrawer.FEET) * k
        return Placement(box, w / 2f - x, h / 2f - y)
    }

    /** Where her heart is in a full-figure placement, as a share of the box from its top. */
    val heartY: Float get() = SpriteLayout.BASELINE + (HEART_Y - RigDrawer.FEET) * SpriteLayout.FILL / (RigDrawer.FEET - RigDrawer.TOP)
}
