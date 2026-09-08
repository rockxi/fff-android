package ru.rockxi.fff.data.remote

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class RemoteJsonTest {
 @Test fun `parses host list without secrets`(){val h=RemoteJson.hosts("""{"hosts":[{"id":"h1","name":"Box","hostname":"10.0.0.2","port":2222,"username":"me","authType":"key","jumpHostId":"jump"}]}""").single();assertEquals("Box",h.name);assertEquals(2222,h.port);assertEquals("jump",h.jumpHostId)}
 @Test fun `parses command exit output and truncation`(){val r=RemoteJson.command("""{"exitCode":7,"stdout":"out","stderr":"err","truncated":true}""");assertEquals(7,r.exitCode);assertEquals("out",r.stdout);assertTrue(r.truncated)}
 @Test fun `parses session and output cursor`(){val s=RemoteJson.session("""{"session":{"id":"s1","hostId":"h1","prompt":"do it","status":"running","title":null,"workingDirectory":"/tmp","exitCode":null}}""");val o=RemoteJson.output("""{"output":"line","nextOffset":4,"complete":false,"truncatedBefore":2}""");assertEquals("running",s.status);assertEquals(4L,o.nextOffset);assertEquals(2L,o.truncatedBefore)}
 @Test fun `command response limit covers aiohttp escaped non bmp output`(){val escapedEmoji="\\ud83d\\ude00";val encoded="""{"exitCode":0,"stdout":"${escapedEmoji.repeat(256_000)}","stderr":"","truncated":false}""".toByteArray();assertTrue(encoded.size>2*1024*1024);assertTrue(encoded.size<HttpRemoteApi.COMMAND_RESPONSE_BYTES);val parsed=RemoteJson.command(String(encoded));assertEquals(256_000,parsed.stdout.codePointCount(0,parsed.stdout.length))}
}
