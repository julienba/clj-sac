# Description
This project is a Clojure low-level toolbox for developing with LLMs.
Tired of re-implementing the same HTTP client for your accessing LLM provider? This library is a good place to centralized the boilerplate.

## Status
Experimental. Use at your own risk.
For a more production toolkit, you should look for [langchain4j](https://github.com/langchain4j/langchain4j)

## Features

See examples directory.

## Achitectures

See [Architecture Documentation](docs/architecture.md) for an overview of the system design.

## Contributions

Contributions are more than welcome! In particular, the `llm/http` namespace is designed to make it easy to add new LLM providers.
Much of the client code can be generated automatically using GenAI, but it is essential that someone with a subscription a human contributor do a integration test.
If you have access to an LLM API that's not yet supported, your help in adding and testing a new client would be greatly appreciated and benefit.
Feel free to open issues, submit pull requests, or suggest improvements!

# Milestones

## Release the minimum version

Implement mistral and Claude using hato. Both sync and async. With schema.
No protocol yet.


## Example of DIY Agent

`clj-sac.agent` contains one example of simple agent using LLM and applying tools until reaching an outcome.

## Future ideas

### Introduce a protocol that abstract which LLM is used

### Add more LLMs support

Obviously.

### Add support for multi modality
Image, etc.
