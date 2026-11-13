package com.phantom.player

import android.content.SharedPreferences
import com.phantom.server.buildPlayer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ResumeStoreTest {

    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)

        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class EditorImpl : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any>()
            private val toRemove = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) temp[key] = value else toRemove.add(key)
                }
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) temp[key] = values else toRemove.add(key)
                }
                return this
            }
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
                if (key != null) toRemove.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) map.clear()
                for (k in toRemove) map.remove(k)
                map.putAll(temp)
            }
        }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testComputeKeySha1() {
        val pageUrl = "https://example.com/watch?v=movie123"
        val mediaUrl1 = "https://cdn.example.com/streams/master.m3u8?token=xyz"
        val mediaUrl2 = "https://cdn.example.com/streams/master.m3u8?token=abc"
        val expectedHash = sha1(pageUrl)

        val key1 = ResumeStore.computeKey(pageUrl, mediaUrl1)
        val key2 = ResumeStore.computeKey(pageUrl, mediaUrl2)
        assertEquals(expectedHash, key1)
        assertEquals(key1, key2)
        assertEquals(40, key1.length)

        // Sayfa adresi #t=10 fragment'li ve fragment'siz -> aynı anahtar
        val pageUrlWithFragment = "https://example.com/watch?v=movie123#t=10"
        val keyWithFragment = ResumeStore.computeKey(pageUrlWithFragment, mediaUrl1)
        assertEquals(key1, keyWithFragment)

        // Farklı sayfa adresi -> farklı anahtar
        val differentPageKey = ResumeStore.computeKey("https://example.com/other", mediaUrl1)
        assertNotEquals(key1, differentPageKey)
    }

    @Test
    fun testResumeThresholdLogic() {
        val dur = 600_000L // 10 minutes

        // <= 60s (1 min) should not resume
        assertFalse(ResumeStore.shouldResume(0L, dur))
        assertFalse(ResumeStore.shouldResume(30_000L, dur))
        assertFalse(ResumeStore.shouldResume(60_000L, dur))

        // > 60s and < (dur - 120s) = 480_000ms should resume
        assertTrue(ResumeStore.shouldResume(60_001L, dur))
        assertTrue(ResumeStore.shouldResume(180_000L, dur))
        assertTrue(ResumeStore.shouldResume(479_999L, dur))

        // >= (dur - 120s) should not resume (within last 2 minutes)
        assertFalse(ResumeStore.shouldResume(480_000L, dur))
        assertFalse(ResumeStore.shouldResume(550_000L, dur))
        assertFalse(ResumeStore.shouldResume(600_000L, dur))

        // Short media where dur <= 120s should not resume
        assertFalse(ResumeStore.shouldResume(65_000L, 120_000L))
        assertFalse(ResumeStore.shouldResume(65_000L, 100_000L))
        assertFalse(ResumeStore.shouldResume(65_000L, 0L))
    }

    @Test
    fun testShouldClearLogic() {
        val dur = 600_000L
        // < (dur - 120s) should not clear
        assertFalse(ResumeStore.shouldClear(300_000L, dur))
        assertFalse(ResumeStore.shouldClear(479_999L, dur))

        // >= (dur - 120s) should clear
        assertTrue(ResumeStore.shouldClear(480_000L, dur))
        assertTrue(ResumeStore.shouldClear(590_000L, dur))
        assertTrue(ResumeStore.shouldClear(600_000L, dur))
    }

    @Test
    fun testMax50EntriesLimit() {
        val prefs = FakeSharedPreferences()
        val dur = 600_000L

        // Save 50 distinct entries
        for (i in 1..50) {
            val key = "key_$i"
            val pos = 100_000L + i
            val timestamp = 1000L * i
            ResumeStore.savePosition(prefs, key, pos, dur, timestamp)
        }

        val allKeys = prefs.all.keys.filter { !it.endsWith("_time") }
        assertEquals(50, allKeys.size)
        // Verify key_1 is present
        assertEquals(100_001L, ResumeStore.getPosition(prefs, "key_1"))

        // Now save the 51st entry with higher timestamp
        ResumeStore.savePosition(prefs, "key_51", 200_000L, dur, timestamp = 1000L * 51)

        val updatedKeys = prefs.all.keys.filter { !it.endsWith("_time") }
        assertEquals(50, updatedKeys.size)
        // Oldest entry (key_1 with timestamp 1000) must have been removed
        assertEquals(0L, ResumeStore.getPosition(prefs, "key_1"))
        assertFalse(prefs.contains("key_1"))
        assertFalse(prefs.contains("key_1_time"))

        // Newest entry must exist
        assertEquals(200_000L, ResumeStore.getPosition(prefs, "key_51"))
        assertTrue(prefs.contains("key_51"))
        assertTrue(prefs.contains("key_51_time"))

        // Updating an existing entry should update its timestamp and keep 50 entries
        ResumeStore.savePosition(prefs, "key_2", 300_000L, dur, timestamp = 1000L * 52)
        val keysAfterUpdate = prefs.all.keys.filter { !it.endsWith("_time") }
        assertEquals(50, keysAfterUpdate.size)
        assertEquals(300_000L, ResumeStore.getPosition(prefs, "key_2"))

        // Now save key_52. The oldest should now be key_3 (since key_2 was updated to 52000)
        ResumeStore.savePosition(prefs, "key_52", 210_000L, dur, timestamp = 1000L * 53)
        assertEquals(50, prefs.all.keys.filter { !it.endsWith("_time") }.size)
        assertFalse(prefs.contains("key_3"))
        assertTrue(prefs.contains("key_2"))
        assertTrue(prefs.contains("key_52"))
    }

    @Test
    fun testClearPositionOnEndThreshold() {
        val prefs = FakeSharedPreferences()
        val dur = 600_000L
        val key = "test_end_key"

        // First save a valid resume position
        ResumeStore.savePosition(prefs, key, 120_000L, dur, 1000L)
        assertEquals(120_000L, ResumeStore.getPosition(prefs, key))

        // Now save at end threshold (pos >= dur - 120_000 = 480_000)
        ResumeStore.savePosition(prefs, key, 485_000L, dur, 2000L)
        assertEquals(0L, ResumeStore.getPosition(prefs, key))
        assertFalse(prefs.contains(key))
        assertFalse(prefs.contains(key + "_time"))
    }

    @Test
    fun testBuildPlayerProtocolWithResume() {
        val jsonStr = buildPlayer(
            on = true,
            playing = true,
            pos = 50_000L,
            dur = 300_000L,
            resume = 75_000L
        )
        val json = JSONObject(jsonStr)
        assertEquals("player", json.getString("t"))
        assertTrue(json.getBoolean("on"))
        assertTrue(json.getBoolean("playing"))
        assertEquals(50_000L, json.getLong("pos"))
        assertEquals(300_000L, json.getLong("dur"))
        assertEquals(75_000L, json.getLong("resume"))

        // When resume is null, "resume" field should not be present
        val jsonNoResume = JSONObject(buildPlayer(on = true, pos = 50_000L, dur = 300_000L))
        assertFalse(jsonNoResume.has("resume"))
    }
}
