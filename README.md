# Dịch Màn Hình (Screen Translator)

Ứng dụng Android dịch chữ trên màn hình (tương tự *Translate On Screen – EZ Screen Translator*).

- Nút nổi trên mọi ứng dụng: **chạm** để dịch toàn màn hình, **nhấn giữ** để chọn vùng cần dịch.
- Hỗ trợ cả hai chế độ chia sẻ của Android 14+: **Toàn bộ màn hình** và **Một ứng dụng**.
- Nhận dạng chữ (OCR):
  - ML Kit trên máy cho chữ La-tinh, Trung, Nhật, Hàn, Devanagari (tự động chọn);
  - Tesseract cho chữ Kirin (Nga, Ukraina...), Hy Lạp, Ả Rập, Do Thái, Thái, Lào, Khmer, Myanmar,
    Gruzia, Armenia và các chữ Ấn Độ khác – dữ liệu ngôn ngữ tải ở lần dùng đầu (vài MB);
  - tự phát hiện chữ Kirin bị đọc nhầm thành chữ La-tinh và đọc lại bằng Tesseract.
- Dịch bằng Google Translate, hỗ trợ hơn 130 ngôn ngữ. Ngôn ngữ của **từng đoạn chữ** được nhận
  diện riêng (ML Kit Language ID) nên màn hình trộn nhiều thứ tiếng vẫn dịch đúng; đoạn đã ở ngôn
  ngữ đích, phiên âm pinyin/furigana và chữ rác được bỏ qua.
- Ghép dòng thành đoạn văn để dịch trọn câu; nhận biết bảng/danh sách để dịch từng ô.
- Làm sạch chữ OCR trước khi dịch: bỏ ký tự rác do đọc nhầm hình ảnh trong video, bỏ khoảng trắng
  thừa giữa chữ Hán/kana; chữ IN HOA toàn bộ được dịch ở dạng chữ thường rồi in hoa lại bản dịch.
- Bản dịch được vẽ đè đúng vị trí chữ gốc; chạm để đóng, nhấn giữ để sao chép.
- **Cài đặt hiển thị bản dịch**: nền trong suốt, màu nền, độ đậm nền, màu chữ, cỡ chữ (tự động/cố định),
  font chữ (12 kiểu), chữ đậm, viền chữ, khung viền – có xem trước trực tiếp.
- Có ô dịch văn bản nhập tay trong ứng dụng.

## AI dịch trên máy (offline)

Màn hình **AI dịch trên máy** (nút trên màn hình chính) cho phép dịch bằng mô hình AI chạy ngay trên
điện thoại qua [llama.cpp](https://github.com/ggml-org/llama.cpp) – không cần mạng, chữ trên màn hình
không gửi đi đâu.

| Mô hình | Dung lượng | Đặc điểm |
|---|---|---|
| HY-MT 1.5 · 1.8B (Tencent) | 1,1 GB | Chuyên dịch, 33 ngôn ngữ, nhanh, nhẹ (RAM ≥ 4 GB) |
| Gemma 4 E2B (Google) + mmproj | 3,4 GB | Nhìn được ảnh chụp: đọc lại chữ OCR sai/bỏ sót, dịch theo ngữ cảnh (RAM ≥ 6–8 GB) |

- **Dịch hay hơn**: cả màn hình được dịch trong một lần, mỗi đoạn bọc thẻ `<sN>` nên AI hiểu ngữ
  cảnh (phụ đề nối tiếp, xưng hô anh/em/tôi/bạn thống nhất, tên riêng chữ Hán đọc theo Hán Việt) mà
  bản dịch vẫn về đúng vị trí từng đoạn. HY-MT dùng đúng mẫu prompt mà mô hình được huấn luyện.
- **Nhận diện chữ thông minh hơn**: AI được dặn sửa lỗi OCR (thiếu dấu, nhầm rn/m, 0/O, chữ Kirin
  bị đọc thành La-tinh…). Với Gemma 4, khi dịch vùng chọn (nhấn giữ nút nổi) AI nhìn chính ảnh chụp
  để đọc lại chữ; khi OCR không thấy chữ nào (chữ nghệ thuật, truyện tranh) AI tự đọc ảnh và dịch.
- Đoạn AI bỏ sót/dịch hỏng được dịch lại riêng; tùy chọn dùng Google Dịch cho phần còn lại.
- Chạm nút nổi khi AI đang chạy để hủy. Mô hình được giải phóng khi dừng dịch hoặc máy thiếu RAM.
- Tải mô hình qua DownloadManager (chạy nền, có thông báo); có thể nhập file GGUF tự chọn.
- Chỉ máy 64-bit (arm64). Thư viện tự chọn nhân tính toán nhanh nhất của CPU (dotprod, i8mm, SVE, SME, KleidiAI).

## Bộ cài

File APK: [`apk/DichManHinh.apk`](apk/DichManHinh.apk) (GitHub Actions tự build sau mỗi lần push).

Yêu cầu Android 8.0 trở lên.

## Kiểm thử tự động

Workflow `E2E emulator` chạy trên emulator Android 14, 15, 16 với các kịch bản: dịch toàn màn hình,
dịch một ứng dụng, ép chia sẻ toàn màn hình, kiểu chữ tùy chỉnh, chọn vùng, ứng dụng chia sẻ bị ẩn,
màn hình cài đặt, đoạn văn tự xuống dòng, video học tiếng Trung (bảng từ vựng) và video tiếng Nga
(dựng lại từ ảnh chụp thực tế). Unit test (`./gradlew testDebugUnitTest`) kiểm tra ghép đoạn,
nhận diện chữ rác, định tuyến ngôn ngữ, chia lô yêu cầu dịch, dựng prompt và đọc kết quả của AI.

Workflow `AI smoke (real models)` dựng `libscreenai.so` cho máy tính và chạy `LocalAiSmokeTest` với
mô hình thật (HY-MT 1.5, Gemma 4 E2B + đọc ảnh) – cùng đường đi Kotlin → JNI → llama.cpp như trên
điện thoại; bản dịch mẫu hiện trong Summary của workflow.

## Build thủ công

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
# Cần Android NDK + CMake (llama.cpp tải tự động; dùng bản có sẵn: -PllamaSrc=/duong/dan/llama.cpp)
```
