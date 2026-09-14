Draft a zopf workflow called "{{name}}" for the workspace at {{workspace}}.

What it should do, in the author's words:

{{description}}

{{context}}

Read {{guide}}/SKILL.md and {{guide}}/references/schema.md before you write anything. They are the
format zopf validates against and the house rules for a graph worth running, and
{{guide}}/references/patterns.md has the shapes worth copying. That guide is written
for an agent with a terminal, so ignore what it says about writing the file, validating it or
running zopf: zopf does all three itself once you reply.

Then read whatever you need to get it right — how the workflows already in this workspace are
written, and, in the repo around it, what the code actually is and which test or lint command is the
real one. Don't write, move or delete anything, and don't run zopf.

Reply with the whole workflow as one fenced yaml block, carrying name: {{name}}. Put anything you
had to guess in a sentence before the block, never in the YAML. If what was asked for is a script
rather than a graph with judgment in it, say so in that sentence and draft the closest thing that
is worth running.
