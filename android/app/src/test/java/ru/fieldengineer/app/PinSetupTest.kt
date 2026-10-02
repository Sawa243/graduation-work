package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fieldengineer.app.domain.PinSetup

class PinSetupTest {
    private val setup = PinSetup()

    @Test
    fun `PIN вводится дважды — после совпадения его можно сохранять`() {
        assertEquals(PinSetup.Result.Confirm, setup.onPin("4071"))
        assertEquals(PinSetup.Step.CONFIRM, setup.step)
        assertEquals(PinSetup.Result.Done("4071"), setup.onPin("4071"))
    }

    @Test
    fun `простой PIN не принимается, шаг не меняется`() {
        assertEquals(PinSetup.Result.Error("PIN из одинаковых цифр слишком простой"), setup.onPin("1111"))
        assertEquals(PinSetup.Result.Error("PIN — от 4 до 6 цифр"), setup.onPin("12"))
        assertEquals(PinSetup.Step.CREATE, setup.step)
    }

    @Test
    fun `повтор не совпал — всё заново, первый PIN забыт`() {
        setup.onPin("4071")
        assertEquals(PinSetup.Result.Error("PIN не совпал, задайте заново"), setup.onPin("4072"))
        assertEquals(PinSetup.Step.CREATE, setup.step)
        // повтор прежнего PIN теперь считается первым вводом, а не подтверждением
        assertEquals(PinSetup.Result.Confirm, setup.onPin("4071"))
    }
}
