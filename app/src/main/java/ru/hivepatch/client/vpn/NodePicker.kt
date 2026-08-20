package ru.hivepatch.client.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import ru.hivepatch.client.data.Node

data class ProbeSample(
    val node: Node,
    val delayMs: Long?,
)

data class PickResult(
    val node: Node,
    val delayMs: Long,
    val samples: List<ProbeSample>,
)

object NodePicker {
    private const val CONCURRENCY = 6
    private const val BUDGET_MS = 22_000L
    private const val STICKY_FACTOR = 1.35

    fun probeOrder(nodes: List<Node>): List<Node> =
        nodes.sortedWith(
            compareBy(
                { if (it.isIntl) 0 else 1 },
                {
                    when (it.profile) {
                        "mobile" -> 0
                        "throne" -> 1
                        else -> 2
                    }
                },
                { it.name },
            ),
        )

    suspend fun pickFastest(
        nodes: List<Node>,
        probeUrl: String,
        preferId: String = "",
        sticky: Boolean = true,
        onSample: (ProbeSample) -> Unit = {},
    ): PickResult = coroutineScope {
        val ordered = probeOrder(nodes)
        val sem = Semaphore(CONCURRENCY)
        val samples = mutableListOf<ProbeSample>()
        val jobs = ordered.map { node ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val ms = XrayCore.measureMs(node, probeUrl)
                    val sample = ProbeSample(node, ms)
                    onSample(sample)
                    synchronized(samples) { samples += sample }
                    sample
                }
            }
        }
        withTimeoutOrNull(BUDGET_MS) { jobs.awaitAll() }
        jobs.forEach { it.cancel() }
        val ok = samples.filter { it.delayMs != null && it.delayMs > 0 }
            .sortedBy { it.delayMs }
        val best = ok.firstOrNull() ?: throw IllegalStateException("нет доступных узлов")
        val stickyPick = if (sticky && preferId.isNotBlank()) {
            ok.firstOrNull { it.node.id == preferId }
        } else {
            null
        }
        val chosen = if (stickyPick != null && stickyPick.delayMs!! <= (best.delayMs!! * STICKY_FACTOR).toLong()) {
            stickyPick
        } else {
            best
        }
        PickResult(chosen.node, chosen.delayMs!!, samples.toList())
    }
}
