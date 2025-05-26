# Description
This project is a Clojure toolbox for developing with LLMs

# TODOs
- [ ] Migrate the http namespace to use hato
- [ ] Call mistral using the http namespace
- [ ] Finish the Mistral implementation using the aboves
- [ ] Rename the project to `sac`: A bag of AI tools.

# Milestones

## Release the minimum version

Implement mistral and Claude using hato. Both sync and async. With schema.
No protocol yet.

## Add examples

Create an example folder the include call to each.
Create agentic like example inspired by the Anthropic paper using Clojure as glue

## Add Gemini support

## Introduce a protocol that abstract which LLM is used

## Add support for multi modality
Image, etc.

## Future ideas

### Add more LLMs support

Obviously.

### Start a prompt management system

Inspired by the Clojure repository that store prompt.
Add facility to read and write prompt


### Start an orchestration namespace

See notion description