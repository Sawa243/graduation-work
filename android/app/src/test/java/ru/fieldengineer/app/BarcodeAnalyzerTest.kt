package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.fieldengineer.app.domain.BarcodeAnalyzer
import ru.fieldengineer.app.domain.CodeKind
import ru.fieldengineer.app.domain.EquipmentRef
import ru.fieldengineer.app.domain.ScanMatch

class BarcodeAnalyzerTest {
    // оборудование объекта 0002-02 из тестовых данных
    private val site = listOf(
        EquipmentRef(4, "BK-EQ-000123", "TST0000D0123", "Eltex MES2428"),
        EquipmentRef(5, "BK-EQ-000124", "TST0000E0124", "Eltex ESR-20"),
    )

    @Test
    fun `метка компании распознаётся и находится`() {
        val code = BarcodeAnalyzer.parse("BK-EQ-000123")!!
        assertEquals(CodeKind.COMPANY_LABEL, code.kind)
        val m = BarcodeAnalyzer.match(code, site, requestEquipmentId = 4) as ScanMatch.Found
        assertEquals(4, m.equipment.id)
        assertTrue(m.isRequestEquipment)
    }

    @Test
    fun `метка в нижнем регистре и с пробелами приводится к виду метки`() {
        val code = BarcodeAnalyzer.parse("  bk-eq-000124 ")!!
        assertEquals("BK-EQ-000124", code.value)
        assertEquals(CodeKind.COMPANY_LABEL, code.kind)
    }

    @Test
    fun `прочий код — заводской номер, ищется без учёта регистра`() {
        val code = BarcodeAnalyzer.parse("tst0000e0124")!!
        assertEquals(CodeKind.SERIAL, code.kind)
        val m = BarcodeAnalyzer.match(code, site, requestEquipmentId = 4) as ScanMatch.Found
        assertEquals(5, m.equipment.id)
        assertFalse(m.isRequestEquipment)   // другое оборудование объекта
    }

    @Test
    fun `похожий на метку, но неверный код — заводской номер`() {
        assertEquals(CodeKind.SERIAL, BarcodeAnalyzer.parse("BK-EQ-12345")!!.kind)
        assertEquals(CodeKind.SERIAL, BarcodeAnalyzer.parse("BK-EQ-0001234")!!.kind)
    }

    @Test
    fun `не найдено на объекте — код сохраняется как есть`() {
        val code = BarcodeAnalyzer.parse("FOC2345X0AB")!!
        val m = BarcodeAnalyzer.match(code, site, requestEquipmentId = null)
        assertTrue(m is ScanMatch.NotFound)
        assertEquals("FOC2345X0AB", m.code.value)
    }

    @Test
    fun `метка оборудования другого объекта не находится`() {
        val m = BarcodeAnalyzer.match(BarcodeAnalyzer.parse("BK-EQ-000101")!!, site, 4)
        assertTrue(m is ScanMatch.NotFound)
    }

    @Test
    fun `пустой код и слишком длинный`() {
        assertNull(BarcodeAnalyzer.parse("   "))
        assertEquals(BarcodeAnalyzer.MAX_LENGTH, BarcodeAnalyzer.parse("X".repeat(150))!!.value.length)
    }
}
