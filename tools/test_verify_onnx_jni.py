"""Small stdlib tests for the release guard's boundary conditions."""
import struct
import unittest

from verify_onnx_jni import class_surface, elf_16k_aligned


class ArtifactGuardTest(unittest.TestCase):
    def elf(self, alignment=16384, file_offset=0, virtual_address=0, kind=1):
        data = bytearray(64 + 56)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, 1)
        struct.pack_into('<IIQQQQQQ', data, 64, kind, 5, file_offset, virtual_address, 0, 128, 128, alignment)
        return data

    def test_16k_and_64k_are_accepted(self):
        self.assertTrue(elf_16k_aligned(self.elf()))
        self.assertTrue(elf_16k_aligned(self.elf(alignment=65536)))

    def test_old_4k_wrapper_is_rejected(self):
        self.assertFalse(elf_16k_aligned(self.elf(alignment=4096)))

    def test_large_alignment_alone_is_insufficient(self):
        self.assertFalse(elf_16k_aligned(self.elf(file_offset=4096)))
        self.assertTrue(elf_16k_aligned(self.elf(file_offset=4096, virtual_address=20480)))

    def test_missing_load_and_wrong_format_are_rejected(self):
        self.assertFalse(elf_16k_aligned(self.elf(kind=4)))
        with self.assertRaises(AssertionError):
            elf_16k_aligned(bytes(120))

    def test_java_constructor_descriptor_is_not_just_a_class_name(self):
        def u2(value): return struct.pack('>H', value)
        def utf(value):
            value = value.encode('ascii')
            return b'\x01' + u2(len(value)) + value
        constants = [utf('ai/onnxruntime/TensorInfo'), b'\x07'+u2(1),
                     utf('java/lang/Object'), b'\x07'+u2(3),
                     utf('<init>'), utf('([J[Ljava/lang/String;I)V')]
        header = b'\xca\xfe\xba\xbe' + u2(0) + u2(52) + u2(7) + b''.join(constants)
        body = u2(1)+u2(2)+u2(4)+u2(0)+u2(0)  # flags, class, super, interfaces, fields
        method = u2(1)+u2(5)+u2(6)+u2(0)
        name, members = class_surface(header + body + u2(1) + method + u2(0))
        self.assertEqual(name, 'Lai/onnxruntime/TensorInfo;')
        self.assertEqual(members['methods'], {('<init>', '([J[Ljava/lang/String;I)V')})
        _, empty = class_surface(header + body + u2(0) + u2(0))
        self.assertFalse(empty['methods'])


if __name__ == '__main__':
    unittest.main()
