package com.phantom.net

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsHelperTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class EditorImpl : SharedPreferences.Editor {
            private val temp = HashMap(data)

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) temp.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                temp.clear()
                return this
            }
            override fun commit(): Boolean {
                data.clear()
                data.putAll(temp)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    @Test
    fun testPersistentMdnsId() {
        val prefs = FakeSharedPreferences()
        val id1 = MdnsHelper.getOrCreatePersistentId(prefs)
        assertEquals(4, id1.length)
        assertTrue(id1.toInt() in 1000..9999)

        // Multiple calls must return exact same ID
        val id2 = MdnsHelper.getOrCreatePersistentId(prefs)
        assertEquals(id1, id2)

        // Even across token changes or pairing resets, prefs store same ID
        val id3 = MdnsHelper.getOrCreatePersistentId(prefs)
        assertEquals(id1, id3)
        assertEquals(MdnsHelper.getHostname(id1), MdnsHelper.getHostname(id3))
    }

    @Test
    fun testGenerate4DigitId() {
        val id1 = MdnsHelper.generate4DigitId("token123")
        val id2 = MdnsHelper.generate4DigitId("token123")
        val idEmpty = MdnsHelper.generate4DigitId("")

        // Deterministic
        assertEquals(id1, id2)
        assertEquals("1000", idEmpty)

        // Always 4 digits
        assertEquals(4, id1.length)
        assertTrue(id1.toInt() in 1000..9999)

        val idOther = MdnsHelper.generate4DigitId("another-random-token-xyz-789")
        assertEquals(4, idOther.length)
        assertTrue(idOther.toInt() in 1000..9999)
    }

    @Test
    fun testGetHostname() {
        val hostname = MdnsHelper.getHostname("4819")
        assertEquals("loungo-4819.local", hostname)
    }

    @Test
    fun testBuildPairUrl() {
        val pairUrl = MdnsHelper.buildPairUrl("loungo-4819.local", "192.168.1.150", 8765, "tok123")
        assertEquals("http://loungo-4819.local:8765/?k=tok123&ip=192.168.1.150", pairUrl)
    }

    @Test
    fun testSelectUrl() {
        val pairUrl = "http://loungo-4819.local:8765/?k=tok123&ip=192.168.1.150"

        // When fallback is false, returns primary .local URL
        val primary = MdnsHelper.selectUrl(pairUrl, useFallback = false)
        assertEquals(pairUrl, primary)

        // When fallback is true, returns IP URL
        val fallback = MdnsHelper.selectUrl(pairUrl, useFallback = true)
        assertEquals("http://192.168.1.150:8765/?k=tok123", fallback)
    }

    @Test
    fun testMdnsPacketBuildingAndParsing() {
        val hostname = "loungo-1234.local"
        val ip = "192.168.1.50"
        val responseBytes = MdnsResponder.buildARecordResponse(hostname, ip)

        assertTrue(responseBytes.size > 12)
        // Check DNS response flag (0x8400)
        val flags = ((responseBytes[2].toInt() and 0xFF) shl 8) or (responseBytes[3].toInt() and 0xFF)
        assertEquals(0x8400, flags)

        // Check Answer count is 1
        val anCount = ((responseBytes[6].toInt() and 0xFF) shl 8) or (responseBytes[7].toInt() and 0xFF)
        assertEquals(1, anCount)
    }

    @Test
    fun testLegacyHostname() {
        assertEquals("phantom-4819.local", MdnsHelper.getLegacyHostname("4819"))
        assertEquals(
            listOf("loungo-4819.local", "phantom-4819.local"),
            MdnsHelper.allHostnames("loungo-4819.local")
        )
    }

    private fun aQuery(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeShort(0); d.writeShort(0); d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(0)
        for (label in name.split('.')) { d.writeByte(label.length); d.write(label.toByteArray()) }
        d.writeByte(0); d.writeShort(1); d.writeShort(1)
        return out.toByteArray()
    }

    @Test
    fun testResponderAnswersNewAndLegacyNames() {
        val names = MdnsHelper.allHostnames("loungo-4819.local")
        for (n in names) {
            val q = aQuery(n)
            assertEquals(listOf(n), MdnsResponder.answeredNames(q, q.size, names))
        }
        val other = aQuery("loungo-9999.local")
        assertTrue(MdnsResponder.answeredNames(other, other.size, names).isEmpty())
    }
}
