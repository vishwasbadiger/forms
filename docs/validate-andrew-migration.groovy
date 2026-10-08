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

def REF_SEARCH_ROOT = TARGET_BU == "all" ? "/content/spglobal"
                                         : "/content/spglobal/${TARGET_BU}"

def REF_PROPS = ["formRef", "formPath", "guideContainerPath", "fileReference"]

def andrewNodes = []   // andrew nodes still present in repo
def staleRefs   = []   // page props still pointing to an "andrew" path
def brokenRefs  = []   // page props pointing to a DAM path that no longer exists

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
println "[STEP 2] Scanning page refs under ${REF_SEARCH_ROOT}\n"
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

                    if (val.toLowerCase().contains("andrew")) {
                        // Ref still points to an old andrew path
                        staleRefs << [nodePath: node.path, prop: prop, val: val]
                        println "  STALE (andrew) [${prop}]"
                        println "    page node : ${node.path}"
                        println "    ref value : ${val}"

                    } else if (val.startsWith("/content/dam/formsanddocuments/")) {
                        // Ref points into DAM — verify the target still exists
                        if (!session.nodeExists(val)) {
                            brokenRefs << [nodePath: node.path, prop: prop, val: val]
                            println "  BROKEN (missing) [${prop}]"
                            println "    page node : ${node.path}"
                            println "    ref value : ${val}  ← node not found"
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        node.nodes.each { child -> walk(child, depth + 1) }
    } catch (Exception ignored) {}
}

if (session.nodeExists(REF_SEARCH_ROOT)) {
    walk(session.getNode(REF_SEARCH_ROOT), 0)
} else {
    println "  SEARCH ROOT NOT FOUND: ${REF_SEARCH_ROOT}"
}

if (staleRefs.isEmpty() && brokenRefs.isEmpty()) {
    println "  ✓  No stale or broken refs found"
}

// ── SUMMARY ───────────────────────────────────────────────────────────────────
line()
println "QA VALIDATION SUMMARY  (BU: ${TARGET_BU})\n"
println "  Andrew nodes still in repo : ${andrewNodes.size()}"
println "  Stale refs (→ andrew path) : ${staleRefs.size()}"
println "  Broken refs (target gone)  : ${brokenRefs.size()}"

if (andrewNodes.isEmpty() && staleRefs.isEmpty() && brokenRefs.isEmpty()) {
    println "\n  ✓  PASS — migration looks complete for '${TARGET_BU}'"
} else {
    println "\n  ✗  FAIL — issues found above need attention"
    if (!staleRefs.isEmpty()) {
        println "\n  Stale refs to fix:"
        staleRefs.each { r -> println "    [${r.prop}] ${r.nodePath}  →  ${r.val}" }
    }
    if (!brokenRefs.isEmpty()) {
        println "\n  Broken refs (DAM target missing — re-run migration for these?):"
        brokenRefs.each { r -> println "    [${r.prop}] ${r.nodePath}  →  ${r.val}" }
    }
}
line()
