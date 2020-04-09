# Scissors

Scissors are needed to cut pieces of yarn, as sometimes
you only need one short piece from a larger ball.

---

**Scissors** is a tool to create mapping layers from different versions
of [Yarn](https://github.com/FabricMC/yarn) or similar mappings.

## Usage

Scissors takes two inputs:
- **A** is the set of mappings where your layer's names come from.
- **B** is the set of mappings that the layer is going to be applied to (e.g. upstream Yarn).

`$ java -jar scissors.jar <input A location> <input B location> <output location>`

By default, all mappings are assumed to be stored as Enigma directories.
This can be changed with command line flags (see `--help`). 
