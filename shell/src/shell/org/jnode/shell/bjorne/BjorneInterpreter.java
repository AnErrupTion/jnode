/*
 * $Id: Command.java 3772 2008-02-10 15:02:53Z lsantha $
 *
 * JNode.org
 * Copyright (C) 2007-2008 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public 
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jnode.shell.bjorne;

import static org.jnode.shell.bjorne.BjorneToken.TOK_CLOBBER;
import static org.jnode.shell.bjorne.BjorneToken.TOK_DGREAT;
import static org.jnode.shell.bjorne.BjorneToken.TOK_DLESS;
import static org.jnode.shell.bjorne.BjorneToken.TOK_DLESSDASH;
import static org.jnode.shell.bjorne.BjorneToken.TOK_GREAT;
import static org.jnode.shell.bjorne.BjorneToken.TOK_GREATAND;
import static org.jnode.shell.bjorne.BjorneToken.TOK_LESS;
import static org.jnode.shell.bjorne.BjorneToken.TOK_LESSAND;
import static org.jnode.shell.bjorne.BjorneToken.TOK_LESSGREAT;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.HashMap;

import org.jnode.driver.console.CompletionInfo;
import org.jnode.shell.CommandInfo;
import org.jnode.shell.CommandInterpreter;
import org.jnode.shell.CommandLine;
import org.jnode.shell.CommandShell;
import org.jnode.shell.CommandThread;
import org.jnode.shell.Completable;
import org.jnode.shell.ShellException;
import org.jnode.shell.ShellFailureException;
import org.jnode.shell.ShellSyntaxException;
import org.jnode.shell.help.CompletionException;
import org.jnode.shell.io.CommandIO;
import org.jnode.shell.syntax.CommandSyntaxException;

/**
 * This is the JNode implementation of the Bourne Shell language.  The long term
 * goal is to faithfully implement the POSIX Shell specification.
 * 
 * @author crawley@jnode.org
 */
public class BjorneInterpreter implements CommandInterpreter {

    public static final int CMD_EMPTY = 0;

    public static final int CMD_COMMAND = 1;

    public static final int CMD_LIST = 2;

    public static final int CMD_FOR = 3;

    public static final int CMD_WHILE = 4;

    public static final int CMD_UNTIL = 5;

    public static final int CMD_IF = 6;

    public static final int CMD_ELIF = 7;

    public static final int CMD_ELSE = 8;

    public static final int CMD_CASE = 9;

    public static final int CMD_SUBSHELL = 10;

    public static final int CMD_BRACE_GROUP = 11;

    public static final int CMD_FUNCTION_DEF = 12;

    public static final int BRANCH_BREAK = 1;

    public static final int BRANCH_CONTINUE = 2;

    public static final int BRANCH_EXIT = 3;

    public static final int BRANCH_RETURN = 4;

    public static final int REDIR_LESS = TOK_LESS;

    public static final int REDIR_GREAT = TOK_GREAT;

    public static final int REDIR_DLESS = TOK_DLESS;

    public static final int REDIR_DLESSDASH = TOK_DLESSDASH;

    public static final int REDIR_DGREAT = TOK_DGREAT;

    public static final int REDIR_LESSAND = TOK_LESSAND;

    public static final int REDIR_GREATAND = TOK_GREATAND;

    public static final int REDIR_LESSGREAT = TOK_LESSGREAT;

    public static final int REDIR_CLOBBER = TOK_CLOBBER;

    public static final int FLAG_ASYNC = 0x0001;

    public static final int FLAG_AND_IF = 0x0002;

    public static final int FLAG_OR_IF = 0x0004;

    public static final int FLAG_BANG = 0x0008;

    public static final int FLAG_PIPE = 0x0010;

    public static final CommandNode EMPTY = 
        new SimpleCommandNode(CMD_EMPTY, new BjorneToken[0]);

    private static HashMap<String, BjorneBuiltin> BUILTINS = 
        new HashMap<String, BjorneBuiltin>();
    
    private static boolean DEBUG = false;

    static {
        BUILTINS.put("break", new BreakBuiltin());
        BUILTINS.put("continue", new ContinueBuiltin());
        BUILTINS.put("exit", new ExitBuiltin());
        BUILTINS.put("return", new ReturnBuiltin());
        BUILTINS.put("source", new SourceBuiltin());
    }

    private CommandShell shell;

    private BjorneContext context;

    public BjorneInterpreter() {
        this.context = new BjorneContext(this);
    }

    @Override
    public String getName() {
        return "bjorne";
    }

    @Override
    public int interpret(CommandShell shell, String command) throws ShellException {
        return interpret(shell, command, null, false);
    }

    @Override
    public Completable parsePartial(CommandShell shell, String partial) throws ShellSyntaxException {
        bindShell(shell);
        BjorneTokenizer tokens = new BjorneTokenizer(partial);
        final CommandNode tree = new BjorneParser(tokens).parse();
        if (tree instanceof BjorneCompletable) {
            return new Completable() {
                @Override
                public void complete(CompletionInfo completions,
                        CommandShell shell) throws CompletionException {
                    ((BjorneCompletable) tree).complete(completions, context, shell);
                }
                
            };
        } else {
            return null;
        }
    }
    
    @Override
    public boolean help(CommandShell shell, String partial, PrintWriter pw) throws ShellException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public String escapeWord(String word) {
        // TODO implement this properly
        return word;
    }

    int interpret(CommandShell shell, String command, OutputStream capture, boolean source) 
        throws ShellException {
        BjorneContext myContext;
        // FIXME ... I think there is something wrong / incomplete with the way I'm handling
        // the contexts here ...
        if (capture == null) {
            bindShell(shell);
            myContext = this.context;
        } else {
            myContext = new BjorneContext(this);
        }
        BjorneTokenizer tokens = new BjorneTokenizer(command);
        CommandNode tree = new BjorneParser(tokens).parse();
        if (tree == null) {
            // An empty command line
            return 0;
        }
        if (DEBUG) {
            System.err.println(tree);
        }
        try {
            if (capture == null) {
                // FIXME ... this may add an empty line to the command history
                shell.addCommandToHistory(command);
            }
            return tree.execute((BjorneContext) myContext);
        } catch (BjorneControlException ex) {
            switch (ex.getControl()) {
                case BRANCH_EXIT:
                    return ex.getCount();
                case BRANCH_BREAK:
                case BRANCH_CONTINUE:
                    return 0;
                case BRANCH_RETURN:
                    return (source) ? ex.getCount() : 1;
                default:
                    throw new ShellFailureException("unknown control " + ex.getControl());
            }
        }
    }

    @Override
    public int interpret(CommandShell shell, Reader reader) throws ShellException {
        // FIXME ... update this to support multi-line commands.
        try {
            BufferedReader br = new BufferedReader(reader);
            String line;
            int rc = 0;
            while ((line = br.readLine()) != null) {
                rc = interpret(shell, line);
            }
            return rc;
        } catch (IOException ex) {
            throw new ShellException("Problem reading command file: " + ex.getMessage(), ex);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ex) {
                    // ignore
                }
            }
        }
    }

    @Override
    public int interpret(CommandShell shell, File file) throws ShellException {
        try {
            return interpret(shell, new FileReader(file));
        } catch (FileNotFoundException ex) {
            throw new ShellException("Problem reading command file: " + ex.getMessage(), ex);
        }
    }
    
    private void bindShell(CommandShell shell) {
        if (this.shell != shell) {
            if (this.shell != null) {
                throw new ShellFailureException("my shell changed");
            }
            this.shell = shell;
        }
    }

    int executeCommand(CommandLine cmdLine, BjorneContext context, CommandIO[] streams) 
        throws ShellException {
        BjorneBuiltin builtin = BUILTINS.get(cmdLine.getCommandName());
        if (builtin != null) {
            // FIXME ... built-in commands should use the Syntax mechanisms so
            // that completion, help, etc work as expected.
            return builtin.invoke(cmdLine, this, context);
        } else {
            cmdLine.setStreams(streams);
            try {
                CommandInfo cmdInfo = cmdLine.parseCommandLine(shell);
                return shell.invoke(cmdLine, cmdInfo);
            } catch (CommandSyntaxException ex) {
                throw new ShellException("Command arguments don't match syntax", ex);
            }
        }
    }

    public BjorneContext createContext() throws ShellFailureException {
        return new BjorneContext(this);
    }

    public CommandShell getShell() {
        return shell;
    }

    public PrintStream resolvePrintStream(CommandIO commandIOIF) {
        return shell.resolvePrintStream(commandIOIF);
    }

    public InputStream resolveInputStream(CommandIO stream) {
        return shell.resolveInputStream(stream);
    }

    public CommandThread fork(CommandLine command, CommandIO[] streams) 
        throws ShellException {
        command.setStreams(streams);
        CommandInfo cmdInfo = command.parseCommandLine(shell);
        return shell.invokeAsynchronous(command, cmdInfo);
    }
}
