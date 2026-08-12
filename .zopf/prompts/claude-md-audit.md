Read CLAUDE.md at the root of this repo, then check it against the code it describes.

The file states its own stakes: the design doc and milestone log it was built from are gone, so it
is the whole record. One part of it cannot be recovered by reading the code — the deliberate
non-goals — and the rest is only useful while it is still true.

The commits that have touched `shared/`, `desktopApp/` and the file itself most recently:

${survey.result}

Work through the file section by section and answer one question per claim: **is this still what
the code does?**

Check hardest where a claim names something specific, because those are the ones that rot silently:

- A file, class or function that is named — does it still exist under that name and still do that?
  `runtime/Interpolation.kt`, `model/NodeRefs.kt`, `store/Discovery.kt`, `ui/theme/KuiverBridge.kt`
  and the rest are all cited by path.
- A rule stated as absolute. "Nothing is ever written into a workspace directory." "The engine walks
  the workflow model, not kuiver's `getTopologicalOrder()`." "A gate releases its concurrency permit;
  a permission wait holds it." Find the code that enforces each and confirm it still does.
- A number. The nested approval deadlines (540 / 570 / 600), and the workflow format version the
  migrations carry files up to.
- A "Deliberate non-goal" that has since been built anyway, or a limitation described as a decision
  that is now just wrong.
- The Commands section — do those gradle tasks still exist and still do what the line says?

One thing is out of scope, deliberately. Do not audit tone, structure or length — the file is long
on purpose and says why.

Report only drift you can point at code for. For each one give:

- the claim, quoted from CLAUDE.md;
- the file and line that contradicts it;
- the correction, written in the file's own voice, ready to paste.

If a section is still accurate, say so in one line and move on. "No drift found" is a good outcome
and the most likely one — do not manufacture findings to fill the report, because the next node in
this workflow edits the file with what you write here.
