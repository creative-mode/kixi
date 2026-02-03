## Case Study

The proposed system aims to centralize and organize the management of tests, questions, simulations, and users in an efficient and secure manner. It allows different types of users to interact with the content and functionalities according to their roles, ensuring traceability and a complete history of all operations.

In the system, each academic year is registered with its start and end dates, allowing the association of classes and periods within specific timeframes. Each period (deadline) is identified by a number and name, provided to organize assessments within the academic year. Subjects contain a code, name, and abbreviation, and are linked to exams to define the content being evaluated. Courses contain a code, name, and description, allowing the organization of classes and connection to tests applicable to specific groups.

Classes are associated with a course and an academic year, and can be identified by code and grade. Each system user has an authentication account, including username, email, password, email verification, and activity status, and is associated with a profile that stores personal data such as name, surname, and photo. Users are assigned roles that determine access permissions and actions within the system, with assignment history recorded in the association table.

The system allows the creation of sessions for each user login, recording token, IP address, expiration data, and last use, ensuring traceability and security.

Exams contain information such as exam type, duration, variant, title, instructions, maximum score, visibility, and the user who created it. Each exam can be associated with a class, course, subject, academic year, and period, allowing for generic or specific exams.

Exams are composed of questions, each with a number, announcement, type (objective or subjective), maximum score, and order of presentation. Questions have associated images for illustration and answer options; each option can be marked as correct or incorrect.

Students take simulations that record the user, the test administered, the school year, start and end dates, time spent, final score, and simulation status. Each simulation contains answers, which may indicate the selected option or the text answered, with a record of the score obtained, whether the answer was correct, and when it was answered.

The system ensures that a test can have multiple questions, a question can have multiple options and images, and a simulation can contain multiple answers. Each user can take multiple simulations, but each simulation belongs to only one user and only one test. The total score for each test is recorded to allow for validation and performance reporting.