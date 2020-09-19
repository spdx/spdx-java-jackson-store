# Spdx-Java-Jackson-Store
Storage for SPDX documents utilizing [Jackson Databind](https://github.com/FasterXML/jackson-databind).

This store supports serializing and deserializing files in JSON, YAML and XML formats.

This library utilizes the [SPDX Java Library Storage Interface](https://github.com/spdx/Spdx-Java-Library#storage-interface) extending the `ExtendedSpdxStore` which allows for utilizing any underlying store which implements the [SPDX Java Library Storage Interface](https://github.com/spdx/Spdx-Java-Library#storage-interface).

# Using the Library

This library is intended to be used in conjunction with the [SPDX Java Library](https://github.com/spdx/Spdx-Java-Library).

Create an instance of a store which implements the [SPDX Java Library Storage Interface](https://github.com/spdx/Spdx-Java-Library#storage-interface).  For example, the [InMemSpdxStore](https://github.com/spdx/Spdx-Java-Library/blob/master/src/main/java/org/spdx/storage/simple/InMemSpdxStore.java) is a simple in-memory storage suitable for simple file serializations and deserializations.

Create an instance of `MultiFormatStore(IModelStore baseStore, Format format)` passing in the instance of a store created above along with the format.  The format is one of the following:

- `JSON` - Compact JSON format
- `JSON_PRETTY` - pretty printed JSON format
- `XML` - XML Format
- 'YAML` - YAML format

# Serializing and Deserializing

This library supports the `ISerializableModelStore` interface for serializing and deserializing files based on the format specified.

# Development Status

Mostly stable - although it has not been widely used.