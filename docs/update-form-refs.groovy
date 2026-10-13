import javax.jcr.Node

// ─────────────────────────────────────────────────────────────────────────────
// Update form refs — one-time run, no form moves
//
// Updates formRef / formPath / guideContainerPath / fileReference on pages
// from original andrew DAM paths to new correct subfolder paths.
//
// MI    : andrew-mi/[form]            → mi/forms/[form]
// Energy: forms/andrew-ci-forms/[form] → forms/ci-forms/[form]
//
// Manual tree walk — no JCR-SQL2 (avoids 100k traversal limit).
// Searches all 5 in-scope BU page trees (cross-BU refs exist).
// ─────────────────────────────────────────────────────────────────────────────

def DRY_RUN = true   // ← set to false to apply

def DAM = "/content/dam/formsanddocuments/spglobal"

// Original andrew DAM paths → new correct paths
// NOTE: keys use + concatenation (not "${...}") to produce String not GString —
// GString keys break Map.containsKey() when compared against String values from JCR.
def REMAP = [
    (DAM + "/mi/andrew-mi/long-with-product-info-visible") :
        (DAM + "/mi/forms/long-with-product-info-visible"),

    (DAM + "/mi/andrew-mi/long-with-product-info-hidden") :
        (DAM + "/mi/forms/long-with-product-info-hidden"),

    (DAM + "/mi/andrew-mi/short-newsletter") :
        (DAM + "/mi/forms/short-newsletter"),

    (DAM + "/mi/andrew-mi/short-interest") :
        (DAM + "/mi/forms/short-interest"),

    (DAM + "/energy/forms/andrew-ci-forms/contact-form") :
        (DAM + "/energy/forms/ci-forms/contact-form"),

    (DAM + "/energy/forms/andrew-ci-forms/contact-sales-form") :
        (DAM + "/energy/forms/ci-forms/contact-sales-form"),

    (DAM + "/energy/forms/andrew-ci-forms/user-form") :
        (DAM + "/energy/forms/ci-forms/user-form"),

    (DAM + "/energy/forms/andrew-ci-forms/user-form-b") :
        (DAM + "/energy/forms/ci-forms/user-form-b"),

    (DAM + "/energy/forms/andrew-ci-forms/subscription-form") :
        (DAM + "/energy/forms/ci-forms/subscription-form"),
]

// Energy: has energy form refs + MI visible form ref
// Corporate: has MI hidden form ref
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

println "\nREMAP keys:"
REMAP.each { k, v -> println "  [${k.class.simpleName}] '${k}'" }

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
