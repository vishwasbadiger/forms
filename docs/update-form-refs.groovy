import javax.jcr.Node

// ─────────────────────────────────────────────────────────────────────────────
// Update form refs — one-time run, no form moves
//
// Updates formRef / formPath / guideContainerPath / fileReference on pages
// from old andrew paths to new correct paths for MI and Energy forms.
// Corporate and Ratings have no refs to update. CI excluded.
//
// Uses JCR-SQL2 exact-value queries (indexed — no traversal limit) per path.
// Run once across all environments after forms are moved.
// ─────────────────────────────────────────────────────────────────────────────

def DRY_RUN = true   // ← set to false to apply

def DAM = "/content/dam/formsanddocuments/spglobal"

// ── Hardcoded old → new DAM path mappings (MI + Energy only) ─────────────────
def REMAP = [

    // MI — andrew-mi → forms
    "${DAM}/mi/andrew-mi/long-with-product-info-visible" :
        "${DAM}/mi/forms/long-with-product-info-visible",

    "${DAM}/mi/andrew-mi/long-with-product-info-hidden" :
        "${DAM}/mi/forms/long-with-product-info-hidden",

    "${DAM}/mi/andrew-mi/short-newsletter" :
        "${DAM}/mi/forms/short-newsletter",

    "${DAM}/mi/andrew-mi/short-interest" :
        "${DAM}/mi/forms/short-interest",

    // Energy — andrew-ci-forms → ci-forms
    "${DAM}/energy/forms/andrew-ci-forms/contact-form" :
        "${DAM}/energy/forms/ci-forms/contact-form",

    "${DAM}/energy/forms/andrew-ci-forms/contact-sales-form" :
        "${DAM}/energy/forms/ci-forms/contact-sales-form",

    "${DAM}/energy/forms/andrew-ci-forms/user-form" :
        "${DAM}/energy/forms/ci-forms/user-form",

    "${DAM}/energy/forms/andrew-ci-forms/user-form-b" :
        "${DAM}/energy/forms/ci-forms/user-form-b",

    "${DAM}/energy/forms/andrew-ci-forms/subscription-form" :
        "${DAM}/energy/forms/ci-forms/subscription-form",
]

// Page trees to search — all 5 in-scope BUs, CI excluded
def SEARCH_ROOTS = ["corporate", "mi", "ratings", "energy", "sustainable1"]
                       .collect { "/content/spglobal/${it}" }

def REF_PROPS = ["formRef", "formPath", "guideContainerPath", "fileReference"]

// ─────────────────────────────────────────────────────────────────────────────

def qm      = session.workspace.queryManager
def updated = 0
def line    = { println "\n" + ("─" * 72) + "\n" }

println "=" * 72
println "  UPDATE FORM REFS  |  DRY_RUN: ${DRY_RUN}"
println "  Mappings: ${REMAP.size()}  |  Searching: ${SEARCH_ROOTS.size()} BU page trees"
println "=" * 72

REMAP.each { oldPath, newPath ->
    def foundAny = false

    REF_PROPS.each { prop ->
        SEARCH_ROOTS.each { searchRoot ->
            if (!session.nodeExists(searchRoot)) return

            def sql = """SELECT * FROM [nt:base] AS s
                         WHERE ISDESCENDANTNODE(s, '${searchRoot}')
                         AND s.[${prop}] = '${oldPath}'"""
            try {
                def result = qm.createQuery(sql, "JCR-SQL2").execute()
                result.nodes.each { Node node ->
                    if (!DRY_RUN) node.setProperty(prop, newPath)
                    println "  ${DRY_RUN ? '[DRY] UPDATE' : 'UPDATED    '} [${prop}] @ ${node.path}"
                    println "    ${oldPath}"
                    println "  → ${newPath}"
                    updated++
                    foundAny = true
                }
            } catch (Exception e) {
                println "  ERROR [${prop}] in ${searchRoot}: ${e.message}"
            }
        }
    }

    if (!foundAny) {
        println "  NO REFS FOUND: ${oldPath.split('/').last()}"
    }
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
