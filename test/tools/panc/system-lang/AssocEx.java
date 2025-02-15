/*
 * This file is part of the Panini project at Iowa State University.
 *
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * For more details and the latest version of this code please see
 * http://paninij.org
 *
 * Contributor(s): Sean L. Mooney
 */

/*
 * @test
 * @summary Simple test of the associate operator
 * @compile AssocEx.java
 */
capsule C1(C2 other, String s) {
    void foo() {}
}

capsule C2() {
    void foo() {}
}


capsule S {
    design {
        C1 c1s[2];
        C2 c2s[4];

        associate(c1s, 0, c2s, 2, 2, "foobar");
    }
}
