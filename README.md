# Description
This project is a Clojure low-level toolbox for developing with LLMs.
Tired of re-implementing the same HTTP client for your accessing LLM provider? This library is a good place to centralized the boilerplate.

## Status
Experimental. Use at your own risk.

## Proposition value

There are many way to interact with LLM api. This library intent to keep a low-level of abstraction (HTTP over abstraction, full model access over generalization of what all mode can do) while limiting the boilerplate require to call LLM.

If you are looking for a higher level of abstraction to interact with LLM, a few pointer in the Java ecosystem:
- [langchain4j](https://github.com/langchain4j/langchain4j) Java implementation of the popular Python library
- [ADK](https://google.github.io/adk-docs/agents/llm-agents/) Google implementation of an agent, it can interact with other LLM using the contribution part of the project

In the Clojure ecosystem:
- [langchain4clj](https://github.com/nandoolle/langchain4clj) A wrapper over langchain4j
- [litellm-clj](https://github.com/unravel-team/litellm-clj) A Clojure port of the popular litellm Python library


## Features

See examples directory.

## Achitectures

See [Architecture Documentation](docs/architecture.md) for an overview of the system design.

## Contributions

Contributions are more than welcome! In particular, the `llm/http` namespace is designed to make it easy to add new LLM providers.
Much of the client code can be generated automatically using Generative AI, but it is essential that someone a human contributor do an integration test.
If you have access to an LLM API that's not yet supported, your help in adding and testing a new client would be greatly appreciated and benefit everyone.
Feel free to open issues, submit pull requests, or suggest improvements!

## Changelog

- Add an `async?` option to `clj-sac/http`, see example of usage [chaining.clj](example/src/chaining.clj)
- Move the `clj-sac/prompt` to its own project [clj-dot-prompt](https://github.com/julienba/clj-dot-prompt)

## Future ideas

### Add more LLMs support

Obviously.

### Add support for multi modality
Image, etc.

### Add Agent-like workflow example

Implement the patterns describe [here](https://www.anthropic.com/engineering/building-effective-agents) using simple building blocks (the http namespace, core-async, ).
Document how to make the workflow durable with Postgres/Redis/...
