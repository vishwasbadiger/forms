import javax.jcr.Node
import javax.jcr.Session

// ─────────────────────────────────────────────────────────────────────────────
// Remove "andrew" folder hierarchy
//
// Scans BOTH storage trees:
//   /content/forms/af/spglobal/             — AEM Forms Manager tree
//   /content/dam/formsanddocuments/spglobal/ — DAM tree (what pages reference)
//
// Moves forms from andrew folders directly to their correct BU subfolders:
//   mi      : mi/andrew-mi/[form]                   → mi/forms/[form]
//   energy  : energy/forms/andrew-ci-forms/[form]   → energy/forms/ci-forms/[form]
//   ratings : ratings/en/forms/andrew-forms/[form]  → ratings/en/forms/[form]
//   corporate: corporate/andrew-test-form            → corporate/andrew-test-form (no change)
//
// Run with DRY_RUN = true first, read the output, then flip to false.
// ─────────────────────────────────────────────────────────────────────────────

def DRY_RUN   = true   // ← set to false to apply

// Set to one of: "corporate" | "mi" | "ratings" | "energy" | "sustainable1" | "ci" | "all"
def TARGET_BU = "energy"

// ─────────────────────────────────────────────────────────────────────────────

def ALL_BUS    = ["corporate", "mi", "ratings", "energy", "sustainable1", "ci"]
def ACTIVE_BUS = TARGET_BU == "all" ? ALL_BUS : [TARGET_BU]

def AF_BU_ROOTS  = ACTIVE_BUS.collect { "/content/forms/af/spglobal/${it}" }
def DAM_BU_ROOTS = ACTIVE_BUS.collect { "/content/dam/formsanddocuments/spglobal/${it}" }

// Per-BU destination subfolder under buRoot.
// null = move directly to buRoot
// path = move into this subfolder under buRoot
def BU_SUBFOLDER = [
    "corporate":   null,             // corporate/test-form  (andrew- stripped from name)
    "mi":          "forms",          // mi/forms/[form]
    "ratings":     "en/forms",       // ratings/en/forms/[form]
    "energy":      "forms/ci-forms", // energy/forms/ci-forms/[form]
    "sustainable1": "en/forms",      // sustainable1/en/forms/[form]
    "ci":          "forms/ci-forms", // ci/forms/ci-forms/[form]
]

// Page trees to scan for ref updates (all 5 in-scope BUs — cross-BU refs exist)
def REF_SEARCH_ROOTS = ["corporate", "mi", "ratings", "energy", "sustainable1"]
                           .collect { "/content/spglobal/${it}" }

def REF_PROPS = ["formRef", "formPath", "guideContainerPath", "fileReference"]

// ─────────────────────────────────────────────────────────────────────────────

def FORM_MOVES     = [:] as LinkedHashMap   // old path → new path
def ANDREW_FOLDERS = [] as LinkedHashSet    // folders to delete after moving

// ─────────────────────────────────────────────────────────────────────────────
// Helper: create missing intermediate folders
// ─────────────────────────────────────────────────────────────────────────────
def ensureParent
ensureParent = { String childPath ->
    def parentPath = childPath.substring(0, childPath.lastIndexOf('/'))
    if (session.nodeExists(parentPath)) return
    ensureParent(parentPath)
    def parentName = parentPath.substring(parentPath.lastIndexOf('/') + 1)
    def grandParent = parentPath.substring(0, parentPath.lastIndexOf('/'))
    if (session.nodeExists(grandParent)) {
        session.getNode(grandParent).addNode(parentName, "sling:Folder")
        println "  CREATED folder: ${parentPath}"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Discovery: walk tree, find andrew folders/forms, build FORM_MOVES
// ─────────────────────────────────────────────────────────────────────────────
def findAndrewForms
findAndrewForms = { Node parentNode, int depth, String buRoot ->
    if (depth > 8) return

    def buName     = buRoot.split("/").last()
    def subfolder  = BU_SUBFOLDER[buName]
    def destBase   = (subfolder != null) ? "${buRoot}/${subfolder}" : buRoot

    parentNode.nodes.each { Node child ->
        if (child.name.toLowerCase().contains("andrew")) {
            def realChildren = []
            child.nodes.each { Node gc -> if (gc.name != "jcr:content") realChildren << gc }

            if (realChildren) {
                // Folder containing forms — move each form to destBase
                // Strip "andrew-" prefix from form name if present
                ANDREW_FOLDERS << child.path
                realChildren.each { Node form ->
                    def cleanName = form.name.replaceFirst(/(?i)^andrew-/, '')
                    if (!cleanName) cleanName = form.name
                    FORM_MOVES[form.path] = "${destBase}/${cleanName}"
                }
            } else {
                // Node itself IS the form (e.g. corporate/form/andrew-test-form)
                // Rename in place — strip "andrew-" prefix, keep same parent folder
                def cleanName = child.name.replaceFirst(/(?i)^andrew-/, '')
                if (!cleanName) cleanName = child.name
                FORM_MOVES[child.path] = "${parentNode.path}/${cleanName}"
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
println DRY_RUN ? "  ⚠  DRY RUN — previewing only, nothing will be saved"
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
    if (!DRY_RUN) {
        ensureParent(dst)
        session.move(src, dst)
        // Update jcr:title — strip "Andrew " / "andrew-" from display title
        try {
            def movedNode = session.getNode(dst)
            def jcrContent = movedNode.hasNode("jcr:content") ? movedNode.getNode("jcr:content") : null
            if (jcrContent) {
                ["jcr:title", "dc:title"].each { titleProp ->
                    if (jcrContent.hasProperty(titleProp)) {
                        def oldTitle = jcrContent.getProperty(titleProp).getString()
                        def newTitle = oldTitle.replaceAll(/(?i)\bandrew[- ]/, '').trim()
                        if (newTitle && newTitle != oldTitle) {
                            jcrContent.setProperty(titleProp, newTitle)
                            println "  TITLE UPDATED: '${oldTitle}' → '${newTitle}'"
                        }
                    }
                    // Also check metadata node (DAM assets)
                    if (jcrContent.hasNode("metadata")) {
                        def meta = jcrContent.getNode("metadata")
                        if (meta.hasProperty(titleProp)) {
                            def oldTitle = meta.getProperty(titleProp).getString()
                            def newTitle = oldTitle.replaceAll(/(?i)\bandrew[- ]/, '').trim()
                            if (newTitle && newTitle != oldTitle) {
                                meta.setProperty(titleProp, newTitle)
                                println "  TITLE UPDATED (metadata): '${oldTitle}' → '${newTitle}'"
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { println "  WARN: could not update title at ${dst}: ${e.message}" }
    }
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
    def folder    = session.getNode(folderPath)
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
println "[STEP 3] Updating content references across 5 BU page trees\n"

def updated = 0

// Only DAM paths — pages never reference AF paths
def DAM_MOVES = FORM_MOVES.findAll { src, dst -> src.startsWith("/content/dam/") }

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
println "SUMMARY"
println "  Forms discovered           : ${FORM_MOVES.size()}"
println "    AF  tree (forms/af)      : ${FORM_MOVES.keySet().count { it.startsWith('/content/forms/af/') }}"
println "    DAM tree (dam/forms...)  : ${FORM_MOVES.keySet().count { it.startsWith('/content/dam/') }}"
println "  Forms moved                : ${moved}"
println "  Forms not found            : ${notFound}"
println "  Destination already exists : ${skipped}"
println "  Andrew folders deleted     : ${deleted}"
println "  Content refs updated       : ${updated}"
if (DRY_RUN) println "\n  ⚠  DRY RUN complete — set DRY_RUN = false at the top to apply"
line()
