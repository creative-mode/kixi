# ADR 0013: Cross-institution ranking and the privacy decisions behind it

## Status
Accepted

---

## Context

`GET /api/v1/leaderboard` was delivered by issue #117 with two scopes: `class` and `school`.
Both resolve the cohort from the seats the caller actually holds, and `scope=school` compares
a student **inside their own school**. The objective of #117 also reads "comparar desempenhos
com alunos de outras instituições", which no scope implements, so it was left behind and
became issue #189.

A ranking is a publication, not a readout. Inside a school it publishes to peers who were
placed together and whose teacher can see the class anyway — a standing context in which the
class ranking is a rendering of something already visible. Crossing the school boundary
removes that standing context, and every protection the existing scopes rely on has to be
re-argued rather than inherited:

- **Consent.** A student never agreed to have their performance read by strangers.
- **Comparability.** Different papers, different difficulty, different maximum scores.
- **Identification by context.** School + class + school year identifies a person in a small
  population even with no name attached.
- **Small groups.** The existing floor (`SMALL_GROUP_LIMIT = 10`) hides the *bottom* of a
  small table. A cross-institution pool is large, so it almost never fires — but the
  opposite failure appears: an institution contributing three students is identifiable by
  elimination, because a one-student institution has a determinate rank.

This ADR records the four decisions taken before any SQL was written. They are product and
privacy decisions, not implementation details, and each one has a code consequence named
below.

---

## Decision

### 1. Opt-in

Cross-institution ranking is **opt-in**. A student appears in `scope=global` rows, and is
rankable by anybody using it, only while they hold a consent row.

- New table `leaderboard_consents`, one row per account, holding `cross_institution`,
  `consented_at` and `revoked_at`. Effective consent is `cross_institution = TRUE AND
  revoked_at IS NULL`.
- Absence of a row means **not consented**. A student who has never been asked is excluded,
  which makes the default the safe one and makes the feature's empty state honest.
- Consent is keyed on the account and not on the school year: a student who consented once
  consented for good, and re-asking every September is worse for consent than it is for
  privacy.
- A caller without consent gets `403`, not an empty ranking. An empty table cannot
  distinguish "I declined" from "nobody sat anything", and the client needs to be able to
  send the student to the consent screen.

`class`, `school` and `friends` are unchanged: they publish to peers, not to strangers.

**Why not opt-out.** Opt-out would mean every existing student is published cross-institution
on the day the feature ships, with nobody's agreement. That is the failure `scope=friends`
had to avoid, arrived at from the other direction.

### 2. Percentile within the student's own institution

Students are compared on their **percentile inside their own institution**, not on their raw
percentage.

- The metric is `CUME_DIST()` over each institution's own distribution, scaled to 0–100:
  "what fraction of my school is below me". Percentile 50 means half of one's own school
  scored lower.
- Raw percentage survives as the tie-break, and as `average` in the response. It is never
  the ranking key.
- The institution is a **partition, not a filter**. A student's own school decides what they
  are compared against; everybody's rows then compete with everybody else's on a scale that
  means the same thing in each school.

`CUME_DIST` rather than `PERCENT_RANK`: `PERCENT_RANK` gives the best student of a 10-student
school 0.9 and of a 500-student school 0.998, so a small school could never reach the top of
a cross-institution table no matter how good its students were. `CUME_DIST` gives the best
student of any school 100. The price is that the weakest student of a small school also
scores high, and that is precisely what decision 4 bounds.

**Why not raw percentage.** 80% on an easy paper and 80% on a hard paper are not the same
result. Ranking them against each other does not compare performance, it compares the
schools' exam curves.

### 3. Peers are a label, not a name

The masking that `class` and `school` apply — first name plus last initial — does not hold
across institutions. Combined with a school, a class and a school year it identifies a
person in a population this small, and a name is not the only channel.

- In `scope=global` a peer is rendered as the neutral label **"Aluno de outra escola"**. No
  masked name, because the name is the thing being removed rather than shortened.
- A peer's `accountId` is `null`, exactly as in every other scope.
- The response carries no `institutionId`, no `classId` and no `schoolYearId` for anybody but
  the caller. `groupId` is `null` for `scope=global`, because there is no group behind it.
- The caller's own row still carries their own id and their own name.

The response publishes `institutionsCounted` and `totalStudents` so the reader can be told
where the comparison comes from, which is what makes an anonymised table legible instead of
ominous.

### 4. k-anonymity on the contributing side

An institution contributes to `scope=global` only if at least
`MIN_STUDENTS_PER_INSTITUTION = 10` of its students have a rankable score and consent, in
that school year. Below the threshold the whole institution is dropped, its students
included and excluded as a block.

- The floor is enforced in SQL, not in Java, and it is applied identically to the rows and to
  the count that decides the podium — so `totalStudents` can never be computed from a
  different set than the rows behind it.
- It is a separate constant from `SMALL_GROUP_LIMIT`, even though both are 10. They answer
  different questions — "is this table too small to have a podium" and "is this school too
  small to be published" — and conflating them is how the elimination channel comes back.
- A student whose own institution is under the threshold is **not** removed from the
  comparison. They simply do not contribute, so their position reflects a smaller field.

**Why this and not a partner allow-list.** An allow-list of institutions answers "which
schools take part", which is a commercial question. The threshold answers "which schools can
be published without identifying anybody", which is the privacy question in front of us, and
it needs no schema change to be correct. A partner list can be added later as an *additional*
filter; it cannot be the prerequisite.

**Accepted consequence.** A rural school with six students never appears in `scope=global`.
That is the trade this decision makes, and it is the correct one: the alternative is
publishing a person who is the only one at their school.

---

## Consequences

- `scope=global` is empty until students opt in. That is the intended launch state.
- `GET /api/v1/leaderboard?scope=global` requires an active seat in a school, and returns
  `403` without one or without consent.
- `PUT /api/v1/leaderboard/consent` is how consent is given and taken back.
- The cached cohort for `scope=global` is shared by every caller: there is one group, so one
  ranking, and every caller receives their own window out of it.
- `LeaderboardResponse` gains `percentile`, `myPercentile` and `institutionsCounted`, and
  `Entry` gains a `percentile` that is `null` outside `scope=global`. This is a
  client-visible change.
- `LeaderboardRepository.Scope` gains `GLOBAL`, and `groupPredicate` refuses it: there is no
  group id behind a cross-institution ranking.

---

## Why these four are written here and not in the issue

The issue could not be implemented without them, and each one changes the shape of the SQL:
consent decides whether membership is an `EXISTS` on a new table; percentile decides whether
the aggregate is wrapped in a window function; anonymity decides whether peer names are read
at all; k-anonymity decides whether an institution filter is applied before the rows are
returned. A pull request that implements this without the decisions would have to be redone,
and — worse — would ship plausible defaults that nobody chose.
