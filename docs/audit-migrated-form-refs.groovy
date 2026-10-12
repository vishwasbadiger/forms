import javax.jcr.Node

// ─────────────────────────────────────────────────────────────────────────────
// Post-Migration Audit — Pages referencing migrated andrew forms (new paths)
// READ-ONLY. Shows which pages now point to each of the 11 migrated forms.
// Download the Groovy Console output as a log for future reference.
// ─────────────────────────────────────────────────────────────────────────────

// Set to one of: "corporate" | "mi" | "ratings" | "energy" | "sustainable1" | "all"
def TARGET_BU = "all"

// ─────────────────────────────────────────────────────────────────────────────
// 11 migrated forms — NEW DAM paths (what pages should now reference)
// ─────────────────────────────────────────────────────────────────────────────
def ALL_FORMS = [
    // corporate
    [ bu: "corporate", name: "andrew-test-form",
      newPath: "/content/dam/formsanddocuments/spglobal/corporate/andrew-test-form" ],

    // mi
    [ bu: "mi", name: "long-with-product-info-visible",
      newPath: "/content/dam/formsanddocuments/spglobal/mi/long-with-product-info-visible" ],
    [ bu: "mi", name: "long-with-product-info-hidden",
      newPath: "/content/dam/formsanddocuments/spglobal/mi/long-with-product-info-hidden" ],
    [ bu: "mi", name: "short-newsletter",
      newPath: "/content/dam/formsanddocuments/spglobal/mi/short-newsletter" ],
    [ bu: "mi", name: "short-interest",
      newPath: "/content/dam/formsanddocuments/spglobal/mi/short-interest" ],

    // ratings
    [ bu: "ratings", name: "ratings-registration",
      newPath: "/content/dam/formsanddocuments/spglobal/ratings/ratings-registration" ],

    // energy
    [ bu: "energy", name: "contact-form",
      newPath: "/content/dam/formsanddocuments/spglobal/energy/contact-form" ],
    [ bu: "energy", name: "contact-sales-form",
      newPath: "/content/dam/formsanddocuments/spglobal/energy/contact-sales-form" ],
    [ bu: "energy", name: "user-form",
      newPath: "/content/dam/formsanddocuments/spglobal/energy/user-form" ],
    [ bu: "energy", name: "user-form-b",
      newPath: "/content/dam/formsanddocuments/spglobal/energy/user-form-b" ],
    [ bu: "energy", name: "subscription-form",
      newPath: "/content/dam/formsanddocuments/spglobal/energy/subscription-form" ],
]

def ACTIVE_FORMS = (TARGET_BU == "all") ? ALL_FORMS : ALL_FORMS.findAll { it.bu == TARGET_BU }

// Page trees to scan (all 5 in-scope BUs — cross-BU refs exist)
def SCAN_ROOTS = ["corporate", "mi", "ratings", "energy", "sustainable1"]
                    .collect { "/content/spglobal/${it}" }

def REF_PROPS = ["formRef", "formPath", "guideContainerPath", "fileReference"]

// ─────────────────────────────────────────────────────────────────────────────

def line = { println "\n" + ("─" * 80) + "\n" }
def results = [:]   // newPath → [ [pageNode, prop], ... ]
ACTIVE_FORMS.each { f -> results[f.newPath] = [] }

// Build a lookup set for fast matching
def TARGET_PATHS = ACTIVE_FORMS.collect { it.newPath } as Set

def walk
walk = { Node node, int depth ->
    if (depth > 20) return
    try {
        REF_PROPS.each { prop ->
            try {
                if (node.hasProperty(prop)) {
                    def val = node.getProperty(prop).getString()
                    if (TARGET_PATHS.contains(val)) {
                        results[val] << [nodePath: node.path, prop: prop]
                    }
                }
            } catch (Exception ignored) {}
        }
        node.nodes.each { child -> walk(child, depth + 1) }
    } catch (Exception ignored) {}
}

println "=" * 80
println "  POST-MIGRATION AUDIT — Migrated Andrew Form References"
println "  BU: ${TARGET_BU}    |    Forms checked: ${ACTIVE_FORMS.size()}"
println "=" * 80

SCAN_ROOTS.each { root ->
    if (!session.nodeExists(root)) return
    walk(session.getNode(root), 0)
}

// ── Print results per BU ───────────────────────────────────────────────────
def buGroups = ACTIVE_FORMS.groupBy { it.bu }
def totalRefs = 0

buGroups.each { bu, forms ->
    line()
    println "  BU: ${bu.toUpperCase()}\n"

    forms.each { form ->
        def refs = results[form.newPath]
        println "  Form  : ${form.name}"
        println "  Path  : ${form.newPath}"
        println "  Refs  : ${refs.size()}"

        if (refs) {
            refs.each { r ->
                println "    [${r.prop}]  ${r.nodePath}"
                totalRefs++
            }
        } else {
            println "    (no pages currently reference this form)"
        }
        println ""
    }
}

line()
println "AUDIT SUMMARY  (BU: ${TARGET_BU})"
println "  Forms audited  : ${ACTIVE_FORMS.size()}"
println "  Total page refs: ${totalRefs}"
line()
