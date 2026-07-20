package com.example.phonecamera

import com.example.phonecamera.network.ControlServer
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    
    private val controlServer = ControlServer()
    private val clientIp = "192.168.1.50"

    @Test
    fun parseCommand_helloWithNoSpaces() {
        val cmd = controlServer.parseCommand("HELLO Pixel-6a 1234", clientIp)
        assertTrue(cmd is ControlServer.Command.Hello)
        val hello = cmd as ControlServer.Command.Hello
        assertEquals("Pixel-6a", hello.deviceName)
        assertEquals("1234", hello.pin)
        assertEquals(clientIp, hello.ip)
    }

    @Test
    fun parseCommand_helloWithSpacesInDeviceName() {
        // Test space splitting protocol fix
        val cmd = controlServer.parseCommand("HELLO Google Pixel 7 Pro 9999", clientIp)
        assertTrue(cmd is ControlServer.Command.Hello)
        val hello = cmd as ControlServer.Command.Hello
        assertEquals("Google Pixel 7 Pro", hello.deviceName)
        assertEquals("9999", hello.pin)
        assertEquals(clientIp, hello.ip)
    }

    @Test
    fun parseCommand_setQuality() {
        val cmd = controlServer.parseCommand("SET_QUALITY 720 5678", clientIp)
        assertTrue(cmd is ControlServer.Command.SetQuality)
        val quality = cmd as ControlServer.Command.SetQuality
        assertEquals(720, quality.heightP)
        assertEquals("5678", quality.pin)
        assertEquals(clientIp, quality.fromIp)
    }

    @Test
    fun parseCommand_setFps() {
        val cmd = controlServer.parseCommand("SET_FPS 30 0000", clientIp)
        assertTrue(cmd is ControlServer.Command.SetFps)
        val fps = cmd as ControlServer.Command.SetFps
        assertEquals(30, fps.fps)
        assertEquals("0000", fps.pin)
        assertEquals(clientIp, fps.fromIp)
    }

    @Test
    fun parseCommand_byeWithSpaces() {
        val cmd = controlServer.parseCommand("BYE My Samsung S23 Ultra 4321", clientIp)
        assertTrue(cmd is ControlServer.Command.Bye)
        val bye = cmd as ControlServer.Command.Bye
        assertEquals("My Samsung S23 Ultra", bye.deviceName)
        assertEquals("4321", bye.pin)
        assertEquals(clientIp, bye.ip)
    }
}