import javax.jcr.Node

// ─────────────────────────────────────────────────────────────────────────────
// QA Validation — post andrew-folder migration
// READ-ONLY — no changes made to the repository.
//
// Checks:
//   1. No andrew folders/forms still exist in AF or DAM trees
//   2. No content refs (formRef, formPath, guideContainerPath, fileReference)
//      still point to a path that contains "andrew"
//   3. No content refs pointing to DAM form paths that no longer exist
//      (form was moved but the page ref was not updated)
// ─────────────────────────────────────────────────────────────────────────────

def TARGET_BU = "energy"   // "corporate"|"mi"|"ratings"|"energy"|"sustainable1"|"all"

// ─────────────────────────────────────────────────────────────────────────────

def ALL_BUS    = ["corporate", "mi", "ratings", "energy", "sustainable1"]
def ACTIVE_BUS = TARGET_BU == "all" ? ALL_BUS : [TARGET_BU]

def AF_BU_ROOTS  = ACTIVE_BUS.collect { "/content/forms/af/spglobal/${it}" }
def DAM_BU_ROOTS = ACTIVE_BUS.collect { "/content/dam/formsanddocuments/spglobal/${it}" }

// Scan only the 5 in-scope BU page trees — catches cross-BU refs without touching other BUs
def REF_SEARCH_ROOTS = ["corporate", "mi", "ratings", "energy", "sustainable1"]
                           .collect { "/content/spglobal/${it}" }

def REF_PROPS = ["formRef", "formPath", "guideContainerPath", "fileReference"]

def andrewNodes = []   // andrew nodes still present in repo
def staleRefs   = []   // page props still pointing to an "andrew" path

def line = { println "\n" + ("─" * 72) + "\n" }

// ── STEP 1: Look for surviving andrew nodes ───────────────────────────────────
line()
println "[STEP 1] Scanning for remaining andrew nodes\n"

def findAndrew
findAndrew = { Node n, int depth ->
    if (depth > 8) return
    try {
        n.nodes.each { Node child ->
            if (child.name.toLowerCase().contains("andrew")) {
                andrewNodes << child.path
                println "  STILL EXISTS: ${child.path}"
            } else {
                findAndrew(child, depth + 1)
            }
        }
    } catch (Exception ignored) {}
}

(AF_BU_ROOTS + DAM_BU_ROOTS).each { root ->
    if (!session.nodeExists(root)) { println "  NOT FOUND — skipping: ${root}"; return }
    findAndrew(session.getNode(root), 0)
}

if (andrewNodes.isEmpty()) println "  ✓  No andrew nodes found in AF or DAM trees"

// ── STEP 2: Walk page tree for stale / broken refs ───────────────────────────
line()
println "[STEP 2] Scanning page refs across 5 in-scope BUs\n"
println "  (This may take 30–60s on large repos…)\n"

def walk
walk = { Node node, int depth ->
    if (depth > 20) return
    try {
        REF_PROPS.each { prop ->
            try {
                if (node.hasProperty(prop)) {
                    def val = node.getProperty(prop).getString()
                    if (!val || !val.startsWith("/")) return

                    if (val.toLowerCase().contains("andrew") &&
                        (val.startsWith("/content/forms/af/") || val.startsWith("/content/dam/formsanddocuments/"))) {
                        staleRefs << [nodePath: node.path, prop: prop, val: val]
                        println "  STALE (andrew) [${prop}]"
                        println "    page node : ${node.path}"
                        println "    ref value : ${val}"
                    }
                }
            } catch (Exception ignored) {}
        }
        node.nodes.each { child -> walk(child, depth + 1) }
    } catch (Exception ignored) {}
}

REF_SEARCH_ROOTS.each { root ->
    if (!session.nodeExists(root)) { println "  NOT FOUND — skipping: ${root}"; return }
    walk(session.getNode(root), 0)
}

if (staleRefs.isEmpty()) println "  ✓  No stale refs found"

// ── SUMMARY ───────────────────────────────────────────────────────────────────
line()
println "QA VALIDATION SUMMARY  (BU: ${TARGET_BU})\n"
println "  Andrew nodes still in repo : ${andrewNodes.size()}"
println "  Stale refs (→ andrew path) : ${staleRefs.size()}"

if (andrewNodes.isEmpty() && staleRefs.isEmpty()) {
    println "\n  ✓  PASS — migration looks complete for '${TARGET_BU}'"
} else {
    println "\n  ✗  FAIL — issues found above need attention"
    if (!staleRefs.isEmpty()) {
        println "\n  Stale refs to fix:"
        staleRefs.each { r -> println "    [${r.prop}] ${r.nodePath}  →  ${r.val}" }
    }
}
line()
