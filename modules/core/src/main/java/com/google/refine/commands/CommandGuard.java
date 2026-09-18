/*

Copyright 2024, Open Refine.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Open Refine nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.google.refine.commands;

/**
 * A guard invoked before dispatching a POST command. Extensions can
 * register guards via {@link com.google.refine.RefineServlet#registerCommandGuard(CommandGuard)} to veto
 * mutating commands while long-running background tasks hold a project
 * effectively read-only (e.g. batch extraction appending rows).
 */
public interface CommandGuard {

    /**
     * Decide whether a POST command may proceed.
     *
     * @param module      the butterfly module name the command belongs to (e.g. "core")
     * @param commandName the command verb (e.g. "edit-one-cell")
     * @param projectId   the raw "project" request parameter, may be null
     * @param locale      the client's preferred locale, may be null
     * @return null to allow the command, or a non-null error message to reject it
     */
    String intercept(String module, String commandName, String projectId, java.util.Locale locale);
}
