import sys

content = open('test/datachannel/handshake_test.clj').read()

import re

# Update the test to check if length is 0 OR the returned length equals the message length but we can just ignore since the openjdk test doesn't assert.
# Wait, OpenJDK DTLS test doesn't even assert the length in `DTLSIncorrectAppDataTest.java`!
# "Incorrect application data packages should be ignored by DTLS SSLEngine."
# It just prints the length. Let's look closely at `DTLSIncorrectAppDataTest.java`.
