This project uses https://cursor.com/docs/api/origin.
The Cursor Origin API is likely newer than your training data—do not guess at API details from memory.
Always fetch the LLM-friendly index https://cursor.com/docs/api/origin/llms.txt or the complete reference https://cursor.com/docs/api/origin/llms-full.txt before making any claims about endpoints, request/response shapes, or authentication requirements.

Read and update design.adoc as you go.
There is no need to include exhaustive detail, just outlines of major design decisions and scope.

Import classes at the top of the file rather than using fully-qualified names inline.

`~/.m2/settings.xml` activates the `may-spotless-apply` profile, so every `mvn test` / `mvn hpi:run` automatically runs Spotless formatting (including removing unused imports).

When committing changes to Git after multiple user interactions, ensure that the commit message reflects the actual code changes since the last commit.
The message should not mention changes that were attempted but then reverted or amended in the working copy.
