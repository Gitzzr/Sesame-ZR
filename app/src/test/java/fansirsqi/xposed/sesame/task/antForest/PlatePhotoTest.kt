package fansirsqi.xposed.sesame.task.antForest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatePhotoTest {

    @Test
    fun `新记录解析出图片地址与抓取时间`() {
        val photo = PlatePhoto.fromRaw(
            mapOf(
                "before" to "idBefore",
                "after" to "idAfter",
                "beforeUrl" to "https://example.com/img/idBefore/original",
                "afterUrl" to "https://example.com/img/idAfter/original",
                "at" to "1790600000000"
            )
        )
        assertEquals("idBefore", photo?.beforeId)
        assertEquals("idAfter", photo?.afterId)
        assertEquals(1790600000000L, photo?.at)
        assertTrue(photo!!.hasPreview)
    }

    @Test
    fun `旧记录只有图片ID时标记为不可预览`() {
        val photo = PlatePhoto.fromRaw(mapOf("before" to "idBefore", "after" to "idAfter"))
        assertEquals("idBefore", photo?.beforeId)
        assertEquals(0L, photo?.at)
        assertFalse(photo!!.hasPreview)
    }

    @Test
    fun `两个ID都取不到时丢弃该条`() {
        assertNull(PlatePhoto.fromRaw(null))
        assertNull(PlatePhoto.fromRaw(emptyMap<String, String>()))
        assertNull(PlatePhoto.fromRaw(mapOf("before" to "", "after" to "")))
    }

    @Test
    fun `坏数据不抛异常`() {
        // 缓存里可能混入非数字的时间戳或 null 值，不能因此让整页打不开
        val photo = PlatePhoto.fromRaw(mapOf("before" to "x", "at" to "not-a-number"))
        assertEquals(0L, photo?.at)
    }
}
