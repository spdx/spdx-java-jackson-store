# Spdx-Java-Jackson-Store
Storage for SPDX documents utilizing [Jackson Databind](https://github.com/FasterXML/jackson-databind).

This store supports serializing and deserializing files in JSON, YAML and XML formats.

The underlying store is in memory, so it may not scale to a large number of documents. It is primarily intended to support reading and writing of SPDX documents in a file format.