# spdx-java-jackson-store

[![javadoc](https://javadoc.io/badge2/org.spdx/spdx-jackson-store/javadoc.svg)](https://javadoc.io/doc/org.spdx/spdx-jackson-store)

Storage for SPDX documents utilizing [Jackson Databind](https://github.com/FasterXML/jackson-databind).

NOTE: This library only supports SPDX Specification version 2.X formats.  For SPDX specification version 3.X formats, please use the [spdx-java-v3jsonld-store](https://github.com/spdx/spdx-java-v3jsonld-store).

This store supports serializing and deserializing files in JSON, YAML and XML formats.

This library utilizes the [SPDX Java Library Storage Interface](https://github.com/spdx/Spdx-Java-Library#storage-interface) extending the `ExtendedSpdxStore` which allows for utilizing any underlying store which implements the [SPDX Java Library Storage Interface](https://github.com/spdx/Spdx-Java-Library#storage-interface).

## Code quality badges

[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=spdx-jackson-store&metric=bugs)](https://sonarcloud.io/dashboard?id=spdx-jackson-store)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=spdx-jackson-store&metric=security_rating)](https://sonarcloud.io/dashboard?id=spdx-jackson-store)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=spdx-jackson-store&metric=sqale_rating)](https://sonarcloud.io/dashboard?id=spdx-jackson-store)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=spdx-jackson-store&metric=sqale_index)](https://sonarcloud.io/dashboard?id=spdx-jackson-store)

## Using the Library

This library is intended to be used in conjunction with the [SPDX Java Library](https://github.com/spdx/Spdx-Java-Library).

Create an instance of a store which implements the [SPDX Java Library Storage Interface](https://github.com/spdx/Spdx-Java-Library#storage-interface).  For example, the [InMemSpdxStore](https://github.com/spdx/Spdx-Java-Library/blob/master/src/main/java/org/spdx/storage/simple/InMemSpdxStore.java) is a simple in-memory storage suitable for simple file serializations and deserializations.

Create an instance of `MultiFormatStore(IModelStore baseStore, Format format)` passing in the instance of a store created above along with the format.  The format is one of the following:

- `JSON` - Compact JSON format
- `JSON_PRETTY` - pretty printed JSON format
- `XML` - XML Format
- `YAML` - YAML format

## Serializing and Deserializing

This library supports the `ISerializableModelStore` interface for serializing and deserializing files based on the format specified.

## API Documentation

- [Released API documentation](https://www.javadoc.io/doc/org.spdx/spdx-jackson-store) (as released on Maven Central)
- [Development API documentation](https://spdx.github.io/spdx-java-jackson-store/) (updated with each GitHub change)

## Development Status

Mostly stable - although it has not been widely used.
