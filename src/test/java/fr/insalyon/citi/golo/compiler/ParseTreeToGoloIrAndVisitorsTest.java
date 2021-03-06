/*
 * Copyright (c) 2012-2015 Institut National des Sciences Appliquées de Lyon (INSA-Lyon)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package fr.insalyon.citi.golo.compiler;

import fr.insalyon.citi.golo.compiler.ir.GoloModule;
import fr.insalyon.citi.golo.compiler.parser.ASTCompilationUnit;
import fr.insalyon.citi.golo.compiler.parser.GoloOffsetParser;
import fr.insalyon.citi.golo.compiler.parser.GoloParser;
import fr.insalyon.citi.golo.compiler.parser.ParseException;
import fr.insalyon.citi.golo.compiler.ir.IrTreeDumper;
import fr.insalyon.citi.golo.internal.testing.TestUtils;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Iterator;

import static fr.insalyon.citi.golo.internal.testing.Tracing.println;
import static fr.insalyon.citi.golo.internal.testing.Tracing.shouldTrace;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

public class ParseTreeToGoloIrAndVisitorsTest {

  @DataProvider(name = "golo-files")
  public static Iterator<Object[]> data() {
    return TestUtils.goloFilesIn("src/test/resources/for-parsing-and-compilation");
  }

  /*
   * Julien:
   *
   * I'm leaving that test method for reference, disabled in regular tests.
   *
   * It is quite useful to debug picky AST <-> IR transformations.
   *
   * I suggest naming the file z_something, as it outputs outputs at the end of the traces for
   * rake test:{parser,visitors,bytecode} which is very very useful.
   */
  @Test(enabled = false)
  public void _debug() throws Throwable {
    GoloParser parser = new GoloOffsetParser(new FileInputStream("src/test/resources/for-parsing-and-compilation/z_debug-operators-fix.golo"), "UTF-8");
    ASTCompilationUnit compilationUnit = parser.CompilationUnit();
    ParseTreeToGoloIrVisitor visitor = new ParseTreeToGoloIrVisitor();

    GoloModule module = null;
    try {
      module = visitor.transform(compilationUnit);
    } catch (GoloCompilationException e) {
      for (GoloCompilationException.Problem problem : e.getProblems()) {
        println("[Problem] " + problem.getDescription());
      }
      throw e;
    }
    dump(module);
  }

  @Test(dataProvider = "golo-files")
  public void convert_then_apply_visitors(File goloFile) throws FileNotFoundException, ParseException {
    GoloParser parser = new GoloOffsetParser(new FileInputStream(goloFile), "UTF-8");
    ASTCompilationUnit compilationUnit = parser.CompilationUnit();
    ParseTreeToGoloIrVisitor visitor = new ParseTreeToGoloIrVisitor();

    GoloModule module = null;
    try {
      module = visitor.transform(compilationUnit);
    } catch (GoloCompilationException e) {
      for (GoloCompilationException.Problem problem : e.getProblems()) {
        println("[Problem] " + problem.getDescription());
      }
      throw e;
    }

    assertThat(module, notNullValue());

    ClosureCaptureGoloIrVisitor closureCaptureVisitor = new ClosureCaptureGoloIrVisitor();
    closureCaptureVisitor.visitModule(module);

    LocalReferenceAssignmentAndVerificationVisitor verificationVisitor = new LocalReferenceAssignmentAndVerificationVisitor();
    verificationVisitor.visitModule(module);

    if (shouldTrace) {
      println();
      println(">>> Building the Golo IR of " + goloFile);
      dump(module);
      println();
    }
  }

  private void dump(GoloModule module) {
    module.accept(new IrTreeDumper());
  }
}
