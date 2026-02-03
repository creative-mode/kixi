## ERM

**schoolYears** (id, startYear, endYear, createdAt, updatedAt, deletedAt); </br>
**terms** (id, number, name, createdAt, updatedAt, deletedAt); </br>
**subjects** (id, code, name, shortName, createdAt, updatedAt, deletedAt); </br>
**courses** (id, code, name, createdAt, updatedAt, deletedAt); </br>
**classes** (id, code, grade, courseId, schoolYearId, createdAt, updatedAt, deletedAt); </br>
**accounts** (id, username, email, passwordHash, emailVerified, lastLogin, createdAt, updatedAt, deletedAt); </br>
**users** (id, accountId, firstName, lastName, photo, createdAt, updatedAt, deletedAt); </br>
**roles** (id, name, description, createdAt, updatedAt, deletedAt); </br>
**accountRoles** (accountId, roleId, createdAt, deletedAt); </br>
**sessions** (id, accountId, token, ipAddress, expiresAt, lastUsed, createdAt, updatedAt, deletedAt); </br>
**statements** (id, examType, durationMinutes, variant, title, instructions, totalMaxScore, schoolYearId, termId, subjectId, classId, courseId, createdBy, visible, createdAt, updatedAt, deletedAt); </br>
**questions** (id, statementId, number, text, questionType, maxScore, orderIndex, createdAt, updatedAt, deletedAt); </br>
**questionImages** (id, questionId, imageUrl, caption, orderIndex, createdAt, updatedAt, deletedAt); </br>
**questionOptions** (id, questionId, optionLabel, optionText, isCorrect, orderIndex, createdAt, updatedAt, deletedAt); </br>
**simulations** (id, accountId, statementId, schoolYearId, startedAt, finishedAt, timeSpentSeconds, finalScore, status, createdAt, updatedAt, deletedAt); </br>
**simulationAnswers** (id, simulationId, questionId, selectedOptionId, answerText, scoreObtained, isCorrect, answeredAt, createdAt, updatedAt, deletedAt); </br>