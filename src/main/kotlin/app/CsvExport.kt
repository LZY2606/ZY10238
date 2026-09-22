package app

object CsvExport {

    private fun esc(x: Any?): String {
        val s = x?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' })
            "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun runCsv(r: AnalysisResult): String = buildString {
        appendLine("# 表型生长轨工坊 运行记录导出")
        appendLine(listOf("section", "key", "trait", "status", "estimate", "se", "detail").joinToString(",") { esc(it) })
        appendLine(listOf("run", r.meta.runId?.toString() ?: "preview", "", "", "", "",
            "label=${r.meta.label};design=${r.meta.designVersion};fixture=${r.meta.fixtureVersion};preprocess=${r.meta.preprocessVersion};boundary=${r.meta.cameraBoundaryDay}/confirmed=${r.meta.cameraBoundaryConfirmed}")
            .joinToString(",") { esc(it) })
        for (o in r.cameraOffsets) {
            appendLine(listOf("camera_offset", "new-vs-old", o.traitId,
                if (o.identifiable) "identifiable" else "unidentifiable",
                o.estimate?.toString() ?: "", o.se?.toString() ?: "",
                "nPairs=${o.nPairs};${o.reason}").joinToString(",") { esc(it) })
        }
        for (o in r.batchOffsets) {
            appendLine(listOf("batch_offset", "${o.anchorBatch}-vs-other", o.traitId,
                if (o.identifiable) "identifiable" else "unidentifiable",
                o.estimate?.toString() ?: "", o.se?.toString() ?: "",
                "nSharedCells=${o.nSharedCells};${o.reason}").joinToString(",") { esc(it) })
        }
        for (c in r.cells) {
            for ((trait, auc) in c.aucByTrait) {
                appendLine(listOf("cell", "${c.genotype}x${c.treatment}", trait, c.status,
                    auc.meanAdjustedAuc?.toString() ?: "", auc.se?.toString() ?: "",
                    "batches=${c.batches.joinToString("/")};n=${auc.nPlants};${c.reason}")
                    .joinToString(",") { esc(it) })
            }
        }
        for (g in r.missingCells) {
            appendLine(listOf("design_gap", "${g.genotype}x${g.treatment}", "", "design_gap",
                "", "", g.reason).joinToString(",") { esc(it) })
        }
        for (e in r.effects) {
            appendLine(listOf("effect", e.key, e.traitId, e.status,
                e.estimate?.toString() ?: "", e.se?.toString() ?: "",
                "${e.kind};${e.reason}").joinToString(",") { esc(it) })
        }
    }
}
