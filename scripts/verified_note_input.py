"""Enter an ASCII fixture note and verify it before leaving the editor."""
import re
import time
import xml.etree.ElementTree as ET


def enter_note(ui, adb, read_xml, output, name, text):
    if not re.fullmatch(r'[A-Za-z ]+', text):
        raise ValueError('This fixture input helper accepts letters and spaces only')

    def field_state():
        raw = read_xml()
        fields = [node for node in ET.fromstring(raw).iter('node')
                  if node.get('class') == 'android.widget.EditText']
        assert len(fields) == 1, 'Expected one visible note editor'
        return fields[0], raw

    def await_field(predicate, description):
        last = None
        for _ in range(6):
            node, raw = field_state()
            last = {'text': node.get('text', ''), 'focused': node.get('focused')}
            if predicate(node):
                return node, raw
            time.sleep(.2)
        raise AssertionError((description, last))

    try:
        field = ui.find(lambda node: node.get('class') == 'android.widget.EditText')
        assert field is not None and ui.click_node(field), 'No enabled visible note editor'
        await_field(lambda node: node.get('focused') == 'true', 'Note editor did not gain focus')
        for _ in range(3):
            adb('shell', 'input', 'keycombination', '113', '29')
            adb('shell', 'input', 'keyevent', '67')
            node, _ = field_state()
            if node.get('focused') == 'true' and node.get('text', '') == '':
                break
        else:
            raise AssertionError('Could not clear the focused note editor')
        # Give Compose each key's state update time to commit before the next key.
        for character in text:
            adb('shell', 'input', 'text', '%s' if character == ' ' else character)
            time.sleep(.15)
        node, raw = await_field(
            lambda node: node.get('focused') == 'true' and node.get('text', '') == text,
            'Typed note differs before hiding the IME',
        )
        (output / (name + '-verified.xml')).write_text(raw)
        (output / (name + '-verified.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(.3)
        return {'inputSetup': name, 'expected': text, 'observed': node.get('text'),
                'focusedBeforeHidingIme': True}
    except Exception:
        # These are bounded diagnostic attempts; they cannot replace the setup error.
        try:
            (output / (name + '-failed.xml')).write_text(read_xml())
        except Exception:
            pass
        try:
            (output / (name + '-failed.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
        except Exception:
            pass
        raise
