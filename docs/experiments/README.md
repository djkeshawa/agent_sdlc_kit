# Experiments

Inputs and results of the experiments in [proposal 02](../proposals/02-mandate-direction.md),
section 9.

## E-A: what the short review view loses

`e-a-tasks.json` lists the 60 tasks selected for experiment E-A from
[CRBugBench](https://github.com/malusamayo/CRBugBench): 30 from its `fix` track (a pull request
introduced a bug that escaped review) and 30 from its `comments` track (a reviewer caught the
bug). All 60 are Java projects that build with Maven, from 13 repositories; 47 of the pull
requests change test files.

Only task ids, repository names and base commits are recorded here. The benchmark states no
licence, so none of its content is copied. Each task builds from the benchmark's own container
image.

Not verified: the build tool was checked at the base commit of one task per repository, and
"changes test files" is read from file paths in each task's diff.
