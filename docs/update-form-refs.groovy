import javax.jcr.Node

// ─────────────────────────────────────────────────────────────────────────────
// Update form refs — one-time run, no form moves
//
// Updates formRef / formPath / guideContainerPath / fileReference on pages
// from old BU-root paths (previous migration) to correct subfolder paths.
//
// Refs per audit:
//   MI    : 2  refs  (1 energy page, 1 corporate page)
//   Energy: 221 refs (all under /content/spglobal/energy)
//   short-newsletter / short-interest: 0 refs — skipped
//
// Manual tree walk — no JCR-SQL2 (avoids 100k traversal limit).
// Scoped to energy + corporate only — that's where all refs are.
// ─────────────────────────────────────────────────────────────────────────────

def DRY_RUN = true   // ← set to false to apply

def DAM = "/content/dam/formsanddocuments/spglobal"

// Old path (BU root after previous migration) → New path (correct subfolder)
def REMAP = [

    // MI — BU root → mi/forms/
    "${DAM}/mi/long-with-product-info-visible" :
        "${DAM}/mi/forms/long-with-product-info-visible",

    "${DAM}/mi/long-with-product-info-hidden" :
        "${DAM}/mi/forms/long-with-product-info-hidden",

    // Energy — energy/forms/ → energy/forms/ci-forms/
    "${DAM}/energy/forms/contact-form" :
        "${DAM}/energy/forms/ci-forms/contact-form",

    "${DAM}/energy/forms/contact-sales-form" :
        "${DAM}/energy/forms/ci-forms/contact-sales-form",

    "${DAM}/energy/forms/user-form" :
        "${DAM}/energy/forms/ci-forms/user-form",

    "${DAM}/energy/forms/user-form-b" :
        "${DAM}/energy/forms/ci-forms/user-form-b",

    "${DAM}/energy/forms/subscription-form" :
        "${DAM}/energy/forms/ci-forms/subscription-form",
]

// Scoped search roots — audit confirms refs only in energy + corporate
def SEARCH_ROOTS = [
    "/content/spglobal/energy",
    "/content/spglobal/corporate",
]

def REF_PROPS = ["formRef", "formPath", "guideContainerPath", "fileReference"]

// ─────────────────────────────────────────────────────────────────────────────

def updated = 0
def line    = { println "\n" + ("─" * 72) + "\n" }

println "=" * 72
println "  UPDATE FORM REFS  |  DRY_RUN: ${DRY_RUN}"
println "  Mappings: ${REMAP.size()}  |  Search roots: ${SEARCH_ROOTS}"
println "=" * 72

def walk
walk = { Node node, int depth ->
    if (depth > 20) return
    try {
        REF_PROPS.each { prop ->
            try {
                if (node.hasProperty(prop)) {
                    def val = node.getProperty(prop).getString()
                    if (REMAP.containsKey(val)) {
                        def newPath = REMAP[val]
                        if (!DRY_RUN) node.setProperty(prop, newPath)
                        println "  ${DRY_RUN ? '[DRY] UPDATE' : 'UPDATED    '} [${prop}] @ ${node.path}"
                        println "    ${val}"
                        println "  → ${newPath}"
                        updated++
                    }
                }
            } catch (Exception ignored) {}
        }
        node.nodes.each { child -> walk(child, depth + 1) }
    } catch (Exception ignored) {}
}

SEARCH_ROOTS.each { root ->
    if (!session.nodeExists(root)) { println "\n  NOT FOUND — skipping: ${root}"; return }
    println "\n  Walking ${root}...\n"
    walk(session.getNode(root), 0)
}

if (!DRY_RUN && updated > 0) {
    session.save()
    println "\n  Saved — ${updated} ref(s) updated"
}

line()
println "SUMMARY"
println "  Refs updated : ${updated}"
if (DRY_RUN) println "  ⚠  DRY RUN — set DRY_RUN = false to apply"
line()
