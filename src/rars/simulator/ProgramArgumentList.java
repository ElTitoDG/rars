package rars.simulator;

import rars.Globals;
import rars.riscv.InstructionSet;
import rars.riscv.hardware.AddressErrorException;
import rars.riscv.hardware.Memory;
import rars.riscv.hardware.RegisterFile;

import java.util.ArrayList;
import java.util.StringTokenizer;

/**
 * Models Program Arguments, one or more strings provided to the source
 * program at runtime. Equivalent to C's main(int argc, char **argv) or
 * Java's main(String[] args).
 *
 * @author Pete Sanderson
 * @version July 2008
 **/

public class ProgramArgumentList {

    private ArrayList<String> programArgumentList;

    public ArrayList<String> getProgramArgumentList() {
        return programArgumentList;
    }

    /**
     * Constructor that parses string to produce list.  Delimiters
     * are the default Java StringTokenizer delimiters (space, tab,
     * newline, return, formfeed)
     *
     * @param args String containing delimiter-separated arguments
     */
    public ProgramArgumentList(String args) {
        StringTokenizer st = new StringTokenizer(args);
        programArgumentList = new ArrayList<>(st.countTokens());
        while (st.hasMoreTokens()) {
            programArgumentList.add(st.nextToken());
        }
    }

    /**
     * Constructor that gets list from String array, one argument per element.
     *
     * @param list Array of String, each element containing one argument
     */
    public ProgramArgumentList(String[] list) {
        this(list, 0);
    }

    /**
     * Constructor that gets list from section of String array, one
     * argument per element.
     *
     * @param list          Array of String, each element containing one argument
     * @param startPosition Index of array element containing the first argument; all remaining
     *                      elements are assumed to contain an argument.
     */
    public ProgramArgumentList(String[] list, int startPosition) {
        programArgumentList = new ArrayList<>(list.length - startPosition);
        for (int i = startPosition; i < list.length; i++) {
            programArgumentList.add(list[i]);
        }
    }

    /**
     * Constructor that gets list from ArrayList of String, one argument per element.
     *
     * @param list ArrayList of String, each element containing one argument
     */
    public ProgramArgumentList(ArrayList<String> list) {
        this(list, 0);
    }


    /**
     * Constructor that gets list from section of String ArrayList, one
     * argument per element.
     *
     * @param list          ArrayList of String, each element containing one argument
     * @param startPosition Index of array element containing the first argument; all remaining
     *                      elements are assumed to contain an argument.
     */
    public ProgramArgumentList(ArrayList<String> list, int startPosition) {
        if (list == null || list.size() < startPosition) {
            programArgumentList = new ArrayList<>(0);
        } else {
            programArgumentList = new ArrayList<>(list.size() - startPosition);
            for (int i = startPosition; i < list.size(); i++) {
                programArgumentList.add(list.get(i));
            }
        }
    }

    //////////////////////////////////////////////////////////////////////
    // Place any program arguments into memory and registers
    // Arguments are stored starting at highest word of non-kernel
    // memory and working back toward runtime stack (there is a 4096
    // byte gap in between).  The argument count (argc) and pointers
    // to the arguments are stored on the runtime stack.  The stack
    // pointer register $sp is adjusted accordingly and $a0 is set
    // to the argument count (argc), and $a1 is set to the stack
    // address holding the first argument pointer (argv).
    public void storeProgramArguments() {
        if (programArgumentList == null || programArgumentList.size() == 0) {
            return;
        }
        // Runtime stack initialization from stack top-down (each is 4 bytes, or 8 byte in 64bit mode) :
        //    programArgumentList.size()
        //    address of first character of first program argument
        //    address of first character of second program argument
        //    ....repeat for all program arguments
        //    0x00000000    (null terminator for list of string pointers)
        // $sp will be set to the address holding the arg list size
        // $a0 will be set to the arg list size (argc)
        // $a1 will be set to stack address just "below" arg list size (argv)
        //
        // Each of the arguments themselves will be stored starting at
        // Memory.stackBaseAddress (0x7ffffffc) and working down from there:
        // 0x7ffffffc will contain null terminator for first arg
        // 0x7ffffffb will contain last character of first arg
        // 0x7ffffffa will contain next-to-last character of first arg
        // Etc down to first character of first arg.
        // Previous address will contain null terminator for second arg
        // Previous-to-that contains last character of second arg
        // Etc down to first character of second arg.
        // Follow this pattern for all remaining arguments.

        int xlen = InstructionSet.rv64 ? 8 : Memory.WORD_LENGTH_BYTES; // width of an integer register (pointer size)

        int highAddress = Memory.stackBaseAddress;  // highest non-kernel address, sits "under" stack
        String programArgument;
        int[] argStartAddress = new int[programArgumentList.size()];
        try { // needed for all memory writes
            for (int i = 0; i < programArgumentList.size(); i++) {
                programArgument = programArgumentList.get(i);
                Globals.memory.set(highAddress, 0, 1);  // trailing null byte for each argument
                highAddress--;
                for (int j = programArgument.length() - 1; j >= 0; j--) {
                    Globals.memory.set(highAddress, programArgument.charAt(j), 1);
                    highAddress--;
                }
                argStartAddress[i] = highAddress + 1;
            }
            // now place a null word, the arg starting addresses, and arg count onto stack.
            int stackAddress = Memory.stackPointer;  // base address for runtime stack.
            if (highAddress < Memory.stackPointer) {
                // Based on current values for stackBaseAddress and stackPointer, this will
                // only happen if the combined lengths of program arguments is greater than
                // 0x7ffffffc - 0x7fffeffc = 0x00001000 = 4096 bytes.  In this case, set
                // stackAddress to next lower word boundary minus 4 for clearance (since every
                // byte from highAddress+1 is filled).
                stackAddress = highAddress - (highAddress % xlen) - xlen;
            }
            Globals.memory.set(stackAddress, 0, xlen);  // null word for end of argv array
            stackAddress -= xlen;
            for (int i = argStartAddress.length - 1; i >= 0; i--) {
                Globals.memory.set(stackAddress, argStartAddress[i], xlen);
                stackAddress -= xlen;
            }
            Globals.memory.set(stackAddress, argStartAddress.length, xlen); // argc
            stackAddress -= xlen;

            // Need to set $sp register to stack address, $a0 to argc, $a1 to argv
            // Need to by-pass the backstepping mechanism so go directly to Register instead of RegisterFile
            RegisterFile.getRegister("sp").setValue(stackAddress + xlen);
            RegisterFile.getRegister("a0").setValue(argStartAddress.length); // argc
            RegisterFile.getRegister("a1").setValue(stackAddress + xlen + xlen); // argv
        } catch (AddressErrorException aee) {
            System.out.println("Internal Error: Memory write error occurred while storing program arguments! " + aee);
            System.exit(0);
        }
    }


}
