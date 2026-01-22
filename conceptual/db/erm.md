## ERM

**schoolYears** (id, startYear, endYear);  </br>
**terms** (id, number);  </br>
**subjects** (id, name);  </br>
**courses** (id, name, description);  </br>
**classes** (id, name, grade);  </br>
**accounts** (
id,
username,
email,
passwordHash,
emailVerified,
active,
createdAt,
lastLogin
);  </br>
**users** (
id,
firstName,
lastName,
photo,
accountId
);  </br>
**roles** (id, name, description);  
accountRoles (id, accountId, roleId);  
**sessions** (
id,
accountId,
token,
createdAt,
expiresAt,
active
);  </br>
**statements** (
id,
examType,
durationMinutes,
variant,
schoolYearId,
termId,
subjectId,
classId,
courseId,
createdBy
);  </br>
**questions** (
id,
number,
text,
score,
statementId
);  </br>
**simulations** (
id,
accountId,
statementId,
date,
finalScore,
timeSpent
);  </br>
**simulationAnswers** (
id,
simulationId,
questionId,
answerText,
correct,
scoreObtained
);  </br>
