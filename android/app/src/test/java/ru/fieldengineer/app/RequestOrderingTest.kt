package ru.fieldengineer.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.fieldengineer.app.domain.currentDue
import ru.fieldengineer.app.domain.sortRequests
import java.time.Instant

class RequestOrderingTest {
    private fun t(s: String) = Instant.parse(s)

    private data class R(val name: String, val finished: Boolean, val reaction: Instant, val resolution: Instant,
                         val arrived: Instant?, val closed: Instant?)

    @Test
    fun `до прибытия действует срок реакции, после — срок устранения`() {
        val reaction = t("2026-10-05T08:30:00Z")
        val resolution = t("2026-10-07T10:30:00Z")
        assertEquals(reaction, currentDue(reaction, resolution, arrivedAt = null))
        assertEquals(resolution, currentDue(reaction, resolution, arrivedAt = t("2026-10-05T07:00:00Z")))
    }

    @Test
    fun `активные по ближайшему сроку, закрытые в конце, недавно закрытые выше`() {
        val list = listOf(
            R("закрыта в 14:40", true, t("2026-09-24T10:40:00Z"), t("2026-09-28T12:40:00Z"), t("2026-09-24T09:05:00Z"), t("2026-09-24T11:40:00Z")),
            // прибыл, поэтому сравнивается срок устранения (позже реакции у «Б»)
            R("А", false, t("2026-10-02T08:30:00Z"), t("2026-10-02T14:30:00Z"), t("2026-10-02T08:05:00Z"), null),
            R("Б", false, t("2026-10-02T13:45:00Z"), t("2026-10-07T06:45:00Z"), null, null),
            R("закрыта в 13:25", true, t("2026-09-25T07:15:00Z"), t("2026-09-30T07:15:00Z"), t("2026-09-24T08:00:00Z"), t("2026-09-24T10:25:00Z")),
            R("В", false, t("2026-10-02T16:40:00Z"), t("2026-10-02T22:40:00Z"), null, null),
        )
        val sorted = sortRequests(list, { it.finished }, { currentDue(it.reaction, it.resolution, it.arrived) },
            { it.closed ?: it.reaction })
        assertEquals(listOf("Б", "А", "В", "закрыта в 14:40", "закрыта в 13:25"), sorted.map { it.name })
    }
}
