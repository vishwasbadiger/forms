import javax.jcr.Node
import javax.jcr.Session

// ─────────────────────────────────────────────────────────────────────────────
// Remove "andrew" folder hierarchy
//
// Scans BOTH storage trees:
//   /content/forms/af/spglobal/         — AEM Forms Manager tree
//   /content/dam/formsanddocuments/spglobal/ — DAM tree (what pages reference)
//
// Steps:
//   1. Discover forms inside *andrew* folders (or nodes whose own name has "andrew")
//   2. Move each form to the BU root level
//   3. Delete now-empty andrew folders
//   4. Update formRef / formPath / etc. on pages (DAM paths only)
//
// Run with DRY_RUN = true first, read the discovery output, then flip to false.
// ─────────────────────────────────────────────────────────────────────────────

def DRY_RUN    = true   // ← set to false to apply

// ── Target BU ─────────────────────────────────────────────────────────────────
// Set to one of: "corporate" | "mi" | "ratings" | "energy" | "sustainable1" | "all"
def TARGET_BU  = "energy"
// ─────────────────────────────────────────────────────────────────────────────

def ALL_BUS = ["corporate", "mi", "ratings", "energy", "sustainable1"]
def ACTIVE_BUS = TARGET_BU == "all" ? ALL_BUS : [TARGET_BU]

def AF_BU_ROOTS  = ACTIVE_BUS.collect { "/content/forms/af/spglobal/${it}" }
def DAM_BU_ROOTS = ACTIVE_BUS.collect { "/content/dam/formsanddocuments/spglobal/${it}" }

// Scan only the 5 in-scope BU page trees — catches cross-BU refs without touching other BUs
def REF_SEARCH_ROOTS = ["corporate", "mi", "ratings", "energy", "sustainable1"]
                           .collect { "/content/spglobal/${it}" }

// Component properties that store a form path (used in Step 3 tree walk)
// "path" intentionally excluded — too generic, not a form reference property

// ─────────────────────────────────────────────────────────────────────────────
// DISCOVERY
// ─────────────────────────────────────────────────────────────────────────────

def FORM_MOVES     = [:] as LinkedHashMap   // old path → new path
def ANDREW_FOLDERS = [] as LinkedHashSet    // folders to delete after moving their contents

// Walk the tree. When a node with "andrew" in its name is encountered:
//   - If it has non-jcr:content children → it is a FOLDER; move its children to buRoot
//   - If it has only jcr:content (or none) → it IS the form; move itself to buRoot
def findAndrewForms
findAndrewForms = { Node parentNode, int depth, String buRoot ->
    if (depth > 8) return

    parentNode.nodes.each { Node child ->
        if (child.name.toLowerCase().contains("andrew")) {
            def realChildren = []
            child.nodes.each { Node gc -> if (gc.name != "jcr:content") realChildren << gc }

            if (realChildren) {
                // Folder: move its contents up to BU root, schedule folder for deletion
                ANDREW_FOLDERS << child.path
                realChildren.each { Node form ->
                    FORM_MOVES[form.path] = buRoot + "/" + form.name
                }
            } else {
                // The node itself is the form (e.g. andrew-test-form)
                FORM_MOVES[child.path] = buRoot + "/" + child.name
            }
        } else {
            findAndrewForms(child, depth + 1, buRoot)
        }
    }
}

println "=== DISCOVERY ===\n"

(AF_BU_ROOTS + DAM_BU_ROOTS).each { buRoot ->
    if (!session.nodeExists(buRoot)) {
        println "  NOT FOUND — skipping: ${buRoot}"
        return
    }
    findAndrewForms(session.getNode(buRoot), 0, buRoot)
}

if (FORM_MOVES.isEmpty()) {
    println "  No andrew forms found — nothing to do."
    return
}

println "  Andrew folders found : ${ANDREW_FOLDERS.size()}"
ANDREW_FOLDERS.each { println "    ${it}" }
println "\n  Forms to move        : ${FORM_MOVES.size()}"
FORM_MOVES.each { src, dst -> println "    ${src}\n      → ${dst}" }

def line = { println "\n" + ("─" * 72) + "\n" }

line()
println DRY_RUN
    ? "  ⚠  DRY RUN — previewing only, nothing will be saved"
    : "  ✔  LIVE RUN — changes WILL be written to the repository"
line()

def moved = 0, skipped = 0, notFound = 0

// ── STEP 1: Move forms ────────────────────────────────────────────────────────
println "[STEP 1] Moving forms\n"

FORM_MOVES.each { src, dst ->
    if (!session.nodeExists(src)) {
        println "  NOT FOUND  : ${src}"
        notFound++
        return
    }
    if (session.nodeExists(dst)) {
        println "  DEST EXISTS: ${src}\n               → ${dst}"
        skipped++
        return
    }
    if (!DRY_RUN) session.move(src, dst)
    println "  ${DRY_RUN ? '[DRY] MOVE ' : 'MOVED      '}: ${src}\n               → ${dst}"
    moved++
}

if (!DRY_RUN && moved > 0) {
    session.save()
    println "\n  Saved — ${moved} move(s) applied"
}

// ── STEP 2: Delete empty andrew folders ──────────────────────────────────────
line()
println "[STEP 2] Deleting empty andrew folders\n"

def deleted = 0
ANDREW_FOLDERS.each { folderPath ->
    if (!session.nodeExists(folderPath)) {
        println "  NOT FOUND  : ${folderPath}"
        return
    }
    def folder = session.getNode(folderPath)
    def remaining = []
    folder.nodes.each { child -> if (child.name != "jcr:content") remaining << child.name }

    if (remaining) {
        println "  NOT EMPTY  : ${folderPath}  (remaining: ${remaining.join(', ')})"
        return
    }
    if (!DRY_RUN) folder.remove()
    println "  ${DRY_RUN ? '[DRY] DELETE' : 'DELETED    '}: ${folderPath}"
    deleted++
}

if (!DRY_RUN && deleted > 0) {
    session.save()
    println "\n  Saved — ${deleted} folder(s) removed"
}

// ── STEP 3: Update content references on pages ───────────────────────────────
line()
println "[STEP 3] Updating content references under ${REF_SEARCH_ROOT}\n"

def updated = 0

// Build a lookup of DAM old-paths only — these are what page components store
def DAM_MOVES = FORM_MOVES.findAll { src, dst -> src.startsWith("/content/dam/") }

def REF_PROPS = ["formRef", "formPath", "guideContainerPath", "fileReference"]

// Manual tree walk — one pass over the page tree, no JCR query, no traversal limits.
// Checks every node for any of the known ref properties and rewrites if matched.
def walk
walk = { Node node, int depth ->
    if (depth > 20) return
    try {
        REF_PROPS.each { prop ->
            try {
                if (node.hasProperty(prop)) {
                    def val = node.getProperty(prop).getString()
                    if (DAM_MOVES.containsKey(val)) {
                        def newPath = DAM_MOVES[val]
                        if (!DRY_RUN) node.setProperty(prop, newPath)
                        println "  ${DRY_RUN ? '[DRY] UPDATE' : 'UPDATED    '} [${prop}] @ ${node.path}"
                        println "               ${val}"
                        println "             → ${newPath}"
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
    println "  Walking ${root}...\n"
    walk(session.getNode(root), 0)
}

if (!DRY_RUN && updated > 0) {
    session.save()
    println "\n  Saved — ${updated} reference(s) updated"
}

// ── Summary ───────────────────────────────────────────────────────────────────
line()
println "SUMMARY"
println "  Forms discovered           : ${FORM_MOVES.size()}"
println "    AF  tree (forms/af)      : ${FORM_MOVES.keySet().count { it.startsWith('/content/forms/af/') }}"
println "    DAM tree (dam/forms...)  : ${FORM_MOVES.keySet().count { it.startsWith('/content/dam/') }}"
println "  Forms moved                : ${moved}"
println "  Forms not found            : ${notFound}"
println "  Destination already exists : ${skipped}"
println "  Andrew folders deleted     : ${deleted}"
println "  Content refs updated       : ${updated}"
if (DRY_RUN) {
    println "\n  ⚠  DRY RUN complete — set DRY_RUN = false at the top to apply"
}
line()
