import javax.jcr.Node
import javax.jcr.Session

// ─────────────────────────────────────────────────────────────────────────────
// Reorganise andrew forms — move from current locations to proper subfolders
//
// Current state  : forms are at BU root (from previous andrew migration)
//                  CI forms are still in ci/forms/andrew-ci-forms/ (was excluded)
//
// New locations  (both AF and DAM trees):
//   energy  : energy/[form]                      → energy/forms/ci-forms/[form]
//   mi      : mi/[form]                          → mi/forms/[form]
//   ratings : ratings/[form]                     → ratings/en/forms/[form]
//   ci      : ci/forms/andrew-ci-forms/[form]    → ci/forms/ci-forms/[form]
//
// Refs on pages (pointing to old DAM BU root paths) are updated to new DAM paths.
// Run with DRY_RUN = true first.
// ─────────────────────────────────────────────────────────────────────────────

def DRY_RUN   = true

// Set to one of: "corporate" | "mi" | "ratings" | "energy" | "all"
def TARGET_BU = "energy"

// ─────────────────────────────────────────────────────────────────────────────
// FORM MOVE MAP  — { afOld, afNew, damOld, damNew }
// ─────────────────────────────────────────────────────────────────────────────
def AF  = "/content/forms/af/spglobal"
def DAM = "/content/dam/formsanddocuments/spglobal"

def ALL_MOVES = [

    // ── CORPORATE ─────────────────────────────────────────────────────────────
    // andrew-test-form → test-form (remove "andrew" from name, stay at BU root)
    [ bu: "corporate", name: "test-form",
      afOld:  "${AF}/corporate/form/andrew-test-form",
      afNew:  "${AF}/corporate/form/test-form",
      damOld: "${DAM}/corporate/form/andrew-test-form",
      damNew: "${DAM}/corporate/form/test-form" ],

    // ── MI ────────────────────────────────────────────────────────────────────
    [ bu: "mi", name: "long-with-product-info-visible",
      afOld:  "${AF}/mi/long-with-product-info-visible",
      afNew:  "${AF}/mi/forms/long-with-product-info-visible",
      damOld: "${DAM}/mi/long-with-product-info-visible",
      damNew: "${DAM}/mi/forms/long-with-product-info-visible" ],

    [ bu: "mi", name: "long-with-product-info-hidden",
      afOld:  "${AF}/mi/long-with-product-info-hidden",
      afNew:  "${AF}/mi/forms/long-with-product-info-hidden",
      damOld: "${DAM}/mi/long-with-product-info-hidden",
      damNew: "${DAM}/mi/forms/long-with-product-info-hidden" ],

    [ bu: "mi", name: "short-newsletter",
      afOld:  "${AF}/mi/short-newsletter",
      afNew:  "${AF}/mi/forms/short-newsletter",
      damOld: "${DAM}/mi/short-newsletter",
      damNew: "${DAM}/mi/forms/short-newsletter" ],

    [ bu: "mi", name: "short-interest",
      afOld:  "${AF}/mi/short-interest",
      afNew:  "${AF}/mi/forms/short-interest",
      damOld: "${DAM}/mi/short-interest",
      damNew: "${DAM}/mi/forms/short-interest" ],

    // ── RATINGS ───────────────────────────────────────────────────────────────
    [ bu: "ratings", name: "ratings-registration",
      afOld:  "${AF}/ratings/ratings-registration",
      afNew:  "${AF}/ratings/en/forms/ratings-registration",
      damOld: "${DAM}/ratings/ratings-registration",
      damNew: "${DAM}/ratings/en/forms/ratings-registration" ],

    // ── ENERGY ────────────────────────────────────────────────────────────────
    [ bu: "energy", name: "contact-form",
      afOld:  "${AF}/energy/contact-form",
      afNew:  "${AF}/energy/forms/ci-forms/contact-form",
      damOld: "${DAM}/energy/contact-form",
      damNew: "${DAM}/energy/forms/ci-forms/contact-form" ],

    [ bu: "energy", name: "contact-sales-form",
      afOld:  "${AF}/energy/contact-sales-form",
      afNew:  "${AF}/energy/forms/ci-forms/contact-sales-form",
      damOld: "${DAM}/energy/contact-sales-form",
      damNew: "${DAM}/energy/forms/ci-forms/contact-sales-form" ],

    [ bu: "energy", name: "user-form",
      afOld:  "${AF}/energy/user-form",
      afNew:  "${AF}/energy/forms/ci-forms/user-form",
      damOld: "${DAM}/energy/user-form",
      damNew: "${DAM}/energy/forms/ci-forms/user-form" ],

    [ bu: "energy", name: "user-form-b",
      afOld:  "${AF}/energy/user-form-b",
      afNew:  "${AF}/energy/forms/ci-forms/user-form-b",
      damOld: "${DAM}/energy/user-form-b",
      damNew: "${DAM}/energy/forms/ci-forms/user-form-b" ],

    [ bu: "energy", name: "subscription-form",
      afOld:  "${AF}/energy/subscription-form",
      afNew:  "${AF}/energy/forms/ci-forms/subscription-form",
      damOld: "${DAM}/energy/subscription-form",
      damNew: "${DAM}/energy/forms/ci-forms/subscription-form" ],

]

def ACTIVE_MOVES = (TARGET_BU == "all") ? ALL_MOVES : ALL_MOVES.findAll { it.bu == TARGET_BU }

// Page trees to scan for ref updates (5 in-scope BUs — cross-BU refs exist)
def REF_SEARCH_ROOTS = ["corporate", "mi", "ratings", "energy", "sustainable1"]
                           .collect { "/content/spglobal/${it}" }
def REF_PROPS = ["formRef", "formPath", "guideContainerPath", "fileReference"]

def line = { println "\n" + ("─" * 72) + "\n" }

// ─────────────────────────────────────────────────────────────────────────────
// Helper: ensure parent node exists (creates sling:Folder nodes as needed)
// ─────────────────────────────────────────────────────────────────────────────
def ensureParent = { String childPath ->
    def parentPath = childPath.substring(0, childPath.lastIndexOf('/'))
    if (session.nodeExists(parentPath)) return
    def parts = parentPath.split('/')
    def current = ""
    parts.each { part ->
        if (!part) return
        current += "/" + part
        if (!session.nodeExists(current)) {
            def parentOfCurrent = current.substring(0, current.lastIndexOf('/'))
            if (!parentOfCurrent) return
            if (session.nodeExists(parentOfCurrent)) {
                session.getNode(parentOfCurrent).addNode(part, "sling:Folder")
                println "  CREATED folder: ${current}"
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
println "=" * 72
println "  REORGANISE FORMS  (BU: ${TARGET_BU}  |  DRY_RUN: ${DRY_RUN})"
println "=" * 72

// ── STEP 1: Move forms ────────────────────────────────────────────────────────
line()
println "[STEP 1] Moving forms to new locations\n"

def moved = 0, skipped = 0, notFound = 0

// Build DAM old→new map for ref update step
def DAM_REMAP = [:]

ACTIVE_MOVES.each { m ->
    [[m.afOld, m.afNew], [m.damOld, m.damNew]].each { oldPath, newPath ->
        if (!session.nodeExists(oldPath)) {
            println "  NOT FOUND  : ${oldPath}"
            notFound++
            return
        }
        if (session.nodeExists(newPath)) {
            println "  DEST EXISTS: ${oldPath}\n               → ${newPath}"
            skipped++
            return
        }
        if (!DRY_RUN) {
            ensureParent(newPath)
            session.move(oldPath, newPath)
        }
        println "  ${DRY_RUN ? '[DRY] MOVE ' : 'MOVED      '}: ${oldPath}\n               → ${newPath}"
        moved++
    }
    DAM_REMAP[m.damOld] = m.damNew
}

if (!DRY_RUN && moved > 0) {
    session.save()
    println "\n  Saved — ${moved} move(s) applied"
}

// ── STEP 2: Update content references on pages ───────────────────────────────
line()
println "[STEP 2] Updating content references across 5 BU page trees\n"

def updated = 0

def walk
walk = { Node node, int depth ->
    if (depth > 20) return
    try {
        REF_PROPS.each { prop ->
            try {
                if (node.hasProperty(prop)) {
                    def val = node.getProperty(prop).getString()
                    if (DAM_REMAP.containsKey(val)) {
                        def newVal = DAM_REMAP[val]
                        if (!DRY_RUN) node.setProperty(prop, newVal)
                        println "  ${DRY_RUN ? '[DRY] UPDATE' : 'UPDATED    '} [${prop}] @ ${node.path}"
                        println "               ${val}"
                        println "             → ${newVal}"
                        updated++
                    }
                }
            } catch (Exception ignored) {}
        }
        node.nodes.each { child -> walk(child, depth + 1) }
    } catch (Exception ignored) {}
}

REF_SEARCH_ROOTS.each { root ->
    if (!session.nodeExists(root)) { println "  NOT FOUND — skipping: ${root}"; return }
    println "  Walking ${root}..."
    walk(session.getNode(root), 0)
    println ""
}

if (!DRY_RUN && updated > 0) {
    session.save()
    println "\n  Saved — ${updated} reference(s) updated"
}

// ── Summary ───────────────────────────────────────────────────────────────────
line()
println "SUMMARY  (BU: ${TARGET_BU})"
println "  Forms moved                : ${moved}"
println "  Forms not found            : ${notFound}"
println "  Destination already exists : ${skipped}"
println "  Content refs updated       : ${updated}"
if (DRY_RUN) println "\n  ⚠  DRY RUN complete — set DRY_RUN = false to apply"
line()
