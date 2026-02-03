Aqui está a tradução completa para inglês do handbook de workflow:

---

# Detailed Project Workflow Handbook (GitHub)

## Objective

This handbook defines the **official workflow** that all project members must follow when working on GitHub. It ensures:

* Organized work and traceability
* Code quality
* Efficient and transparent collaboration
* Clear history for future reference

The official flow is:

> **Issue → Branch → Commit → Pull Request (Code Review) → Merge**

**GitHub Projects** will be used to manage all work (backlog, in progress, in review, done), ensuring full visibility of progress.

---

## Workflow Overview

The complete workflow involves the following elements:

1. **Issue** – defines what needs to be done
2. **Branch** – isolated environment to work on the Issue
3. **Commit** – smallest unit of code change
4. **Pull Request (PR)** – proposal to integrate changes
5. **Code Review** – review of code for quality and compliance
6. **Merge** – final integration into the main branch

Each step has rules, responsibilities, and best practices, detailed below.

---

## Issues

### Definition

An **Issue** is a task or problem to be solved. It can represent:

* A bug to fix
* A new feature
* Refactoring or code improvement
* Documentation or tests

### Responsibilities

* **Issues are created exclusively by the Project Manager, Abner Lourenço**
* Assign labels (`bug`, `feature`, `refactor`, etc.)
* Define acceptance criteria or expected results
* Associate with milestones or epics when applicable

### Best Practices

* An Issue should have **a single objective**
* Avoid very large Issues: split into subtasks if needed
* Reference any relevant resources (documentation, designs, etc.)

---

## Branches

### Definition

A **Branch** is an isolated copy of the code, allowing work on the Issue without affecting the main branch.

### Main Rules

* Each Issue must have its own Branch
* Branch created from the **main branch** (`main` or `develop`)
* Descriptive name, following the pattern:

```
<type>/<issue-id>-<short-description>
```

Examples:

* `feature/23-login-with-jwt`
* `bug/45-fix-null-pointer`
* `refactor/12-service-cleanup`

### Responsibilities

* Ensure code isolation until ready
* Keep the branch up to date with the main branch to avoid conflicts
* Do not commit directly to the main branch

---

## Commits

### Definition

A **commit** represents a unit of code change, which should be logical and cohesive.

### Best Practices

* Small and frequent commits
* Each commit should represent **a specific change**
* Code should compile and pass tests (if any)
* Avoid generic or overly large commits

### Recommended Commit Messages (Simplified Conventional Commits)

```
<type>: short description
```

Common types:

* `feat:` new feature
* `fix:` bug fix
* `refactor:` refactoring
* `docs:` documentation
* `test:` tests
* `chore:` internal tasks

Examples:

* `feat: add JWT authentication`
* `fix: correct user save error`

### Commit Responsibilities

* Clearly record changes
* Associate each commit with the Issue when possible (referencing the number)
* Ensure consistency and readability

---

## Pull Request (PR)

### Definition

A **PR** is the proposal to merge the Issue branch into the main branch.

### When to Create a PR

* When the Issue is complete
* Or when early feedback is needed

### Mandatory Rules

* PR must **always be linked to an Issue** (`Closes #n`)
* PR targets the main branch
* PR without review **cannot be merged**

### PR Content

* Clear description of what was done
* Related Issue
* Screenshots or logs (if applicable)
* Testing instructions, if necessary

### Author Responsibilities

* Clearly explain the changes
* Ensure commits are organized
* Respond to Code Review comments

---

## Code Review

### Definition

**Code Review** is the process of reviewing code by other team members before merging.

### Objective

* Ensure code quality and consistency
* Detect bugs and design issues
* Improve readability and maintainability
* Share knowledge among team members

### Rules

* At least **one approval** required (or more, as defined)
* Clear and respectful comments
* PR author must review and respond to comments
* Suggestions for improvement are welcome

---

## Merge

### Definition

**Merge** is the final integration of approved changes into the main branch.

### Rules and Responsibilities

* Only merge after approval and passing automated checks

* Resolve conflicts before merging

* Recommended strategies:

  * **Squash and merge:** for a clean history
  * **Merge commit:** if you want to preserve detailed history

* Never merge without review

### Post-Merge

* Automatically or manually close the Issue
* Move the card in Project to **Done**
* Delete the Issue branch to keep the repository clean

---

## Golden Rules Summary

* No Issue → no code
* One Issue → one Branch
* Clear, small, frequent commits
* PR required before merge
* Code Review mandatory
* Never commit directly to the main branch
* Post-merge: close Issue, move card, delete branch

---

## Final Considerations

This workflow is **mandatory for all project members**. It ensures:

* Organized and transparent collaboration
* Quality, traceable code
* Clear history for future reference

Follow the flow, document your changes, and keep the repository healthy.
