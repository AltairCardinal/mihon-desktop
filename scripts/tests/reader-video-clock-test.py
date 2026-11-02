import io
import unittest
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from reader_video_clock import read_packet, map_monotonic_pts

class PacketClockTest(unittest.TestCase):
    def test_exact_raw_pts_and_split_reads(self):
        class Short(io.BytesIO):
            def read(self, n=-1): return super().read(min(n, 2))
        pts=1234567890123
        header=((1<<61)|pts).to_bytes(8,"big")+(3).to_bytes(4,"big")
        packet=read_packet(Short(header+b"abc"))
        self.assertEqual(packet["pts_us"],pts)
        self.assertEqual(packet["header"],header)
        self.assertTrue(packet["keyframe"])
        self.assertEqual(packet["payload"],b"abc")
    def test_config_has_no_time_and_truncation_fails(self):
        packet=read_packet(io.BytesIO((1<<62).to_bytes(8,"big")+bytes(4)))
        self.assertIsNone(packet["pts_us"])
        with self.assertRaises(EOFError): read_packet(io.BytesIO(bytes(11)))
    def test_helper_bounds_drive_mapping_and_quantization(self):
        import reader_video_clock
        mapped=reader_video_clock.map_offset_bounds(1234,8,12)
        self.assertEqual(mapped["trace_ns"],1234010)
        self.assertEqual(mapped["trace_bounds_ns"],[1233008,1235012])

    def test_truncated_payload_and_excess_size_fail(self):
        with self.assertRaises(EOFError):
            read_packet(io.BytesIO(bytes(8)+(4).to_bytes(4,"big")+b"abc"))
        with self.assertRaises(ValueError):
            read_packet(io.BytesIO(bytes(8)+(64*1024*1024+1).to_bytes(4,"big")))

    def test_unexpected_session_header_fails(self):
        with self.assertRaises(ValueError):
            read_packet(io.BytesIO((1<<63).to_bytes(8,"big")+bytes(4)))

    def test_invalid_clock_bounds_fail(self):
        import reader_video_clock
        with self.assertRaises(ValueError): reader_video_clock.map_offset_bounds(1234,12,8)
        with self.assertRaises(ValueError): map_monotonic_pts(1234,[])

    def test_cli_reads_lossless_packets_and_preserves_existing_output(self):
        import tempfile, subprocess, json
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            raw=root/"video.packets";out=root/"headers.jsonl"
            raw.write_bytes((1<<62).to_bytes(8,"big")+(1).to_bytes(4,"big")+b"c"+(1234).to_bytes(8,"big")+(3).to_bytes(4,"big")+b"abc")
            command=[sys.executable,str(Path(__file__).resolve().parents[1]/"reader_video_clock.py"),str(raw),str(out),"--offset-low-ns","8","--offset-high-ns","12"]
            flags=getattr(subprocess,"CREATE_NO_WINDOW",0)
            result=subprocess.run(command,capture_output=True,timeout=5,creationflags=flags)
            self.assertEqual(result.returncode,0,result.stderr)
            records=[json.loads(line) for line in out.read_text(encoding="utf-8").splitlines()]
            self.assertIsNone(records[0]["pts_us"])
            self.assertEqual(records[1]["trace_bounds_ns"],[1233008,1235012])
            original=out.read_bytes()
            result=subprocess.run(command,capture_output=True,timeout=5,creationflags=flags)
            self.assertNotEqual(result.returncode,0)
            self.assertEqual(out.read_bytes(),original)

    def test_clock_snapshot_offset_and_bound(self):
        mapped=map_monotonic_pts(1_000_000,[(2_000_000_012,2_000_000_000),(3_000_000_008,3_000_000_000)])
        self.assertEqual(mapped["trace_ns"],1_000_000_010)
        self.assertEqual(mapped["snapshot_offset_range_ns"],[8,12])
        self.assertEqual(mapped["snapshot_residual_bound_ns"],2)

if __name__ == "__main__": unittest.main()
