## ERM

**schoolYears** (id, startYear, endYear, createdAt, updatedAt, deletedAt); </br>
**terms** (id, number, name, createdAt, updatedAt, deletedAt); </br>
**subjects** (id, code, name, shortName, createdAt, updatedAt, deletedAt); </br>
**courses** (id, code, name, description, institutionId, createdAt, updatedAt, deletedAt); </br>
**classes** (id, code, grade, courseId, schoolYearId, institutionId, createdAt, updatedAt, deletedAt); </br>
**accounts** (id, username, email, passwordHash, emailVerified, active, lastLogin, createdAt, updatedAt, deletedAt); </br>
**users** (id, accountId, firstName, lastName, photo, createdAt, updatedAt, deletedAt); </br>
**roles** (id, name, description, createdAt, updatedAt, deletedAt); </br>
**accountRoles** (accountId, roleId, createdAt, deletedAt); </br>
**sessions** (id, accountId, token, ipAddress, expiresAt, lastUsed, createdAt, updatedAt, deletedAt); </br>
**institutions** (id, code, name, shortName, logo, createdAt, updatedAt, deletedAt); </br>
**teachers** (id, accountId, firstName, lastName, email, photo, specialty, employeeNumber, createdAt, updatedAt, deletedAt); </br>
**institutionSubjects** (id, institutionId, subjectId, createdAt, deletedAt); </br>
**institutionTeachers** (id, institutionId, teacherId, createdAt, deletedAt); </br>
**institutionStudents** (id, institutionId, userId, createdAt, deletedAt); </br>
**enrollments** (id, accountId, classId, schoolYearId, status, createdAt, updatedAt, deletedAt); </br>
**teachingAssignments** (id, teacherId, classId, subjectId, schoolYearId, tutorStyle, createdAt, deletedAt); </br>
**statements** (id, examType, durationMinutes, variant, title, instructions, totalMaxScore, schoolYearId, termId, subjectId, classId, courseId, institutionId, createdBy, visible, createdAt, updatedAt, deletedAt); </br>
**questions** (id, statementId, number, text, questionType, maxScore, orderIndex, createdAt, updatedAt, deletedAt); </br>
**questionImages** (id, questionId, imageUrl, caption, orderIndex, createdAt, updatedAt, deletedAt); </br>
**questionOptions** (id, questionId, optionLabel, optionText, isCorrect, orderIndex, createdAt, updatedAt, deletedAt); </br>
**simulations** (id, accountId, statementId, schoolYearId, startedAt, finishedAt, timeSpentSeconds, finalScore, status, createdAt, updatedAt, deletedAt); </br>
**simulationAnswers** (id, simulationId, questionId, selectedOptionId, answerText, scoreObtained, isCorrect, answeredAt, createdAt, updatedAt, deletedAt); </br>
## Notas de modelação

**A escola vive no curso, e a turma herda-a.** `courses.institutionId` é a escola que
oferece o curso; `classes.institutionId` tem de ser igual, e a base de dados garante-o com
uma chave estrangeira composta sobre `(courseId, institutionId)`. Guardar a escola
também na turma seria redundante e criaria a possibilidade de divergirem.

`institutionId` é um id opaco para o módulo `academic`, que não depende do módulo
`institutions` (ver `ArchitectureTest`): o nome da escola é resolvido por `institutions`,
que é quem possui as escolas.

**`institution_students` é uma afiliação administrativa, não a matrícula.** Um aluno
matricula-se em `enrollments` (uma turma, um ano letivo) e a escola vem daí. A ligação em
`institution_students` continua a existir para quando um administrador quer fixar a escola
de uma pessoa sem a meter numa turma; quando existe, tem precedência sobre a deduzida.

**`codes` são únicos por tabela, não por escola.** `courses.code` é único globalmente, o
que na prática significa que cada escola prefixa os seus códigos. Partilhar um código entre
escolas exigiria `UNIQUE (institution_id, code)` e fica para uma migração própria.
