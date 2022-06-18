# RPM Authoring Tools
This set of command line tools facilitates the creation, analysis and usage of [libRPM](https://github.com/HelloOO7/libRPM)'s executable format files.
## RPMDump
RPMDump logs a complete summary of an input RPM file into the console or a text file for better inspection of relocation and symbol tables. It is located at `rpm.cli.RPMDump`.
## RPMTool
RPMTool allows creating and modifying RPM executable modules. The program can be used for converting ELF files into RPMs, editing external relocation tables and merging several modules together. The classpath for RPMTool is `rpm.cli.RPMTool`.
## MAP2ESDB
MAP2ESDB is designed to automatically convert most common linker address map files into RPMTool-compatible YML maps. Note that MAPs that originate from the Interactive Disassembler (IDA) often have malformed segment starting addresses.
