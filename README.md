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

## Bộ cài

File APK: [`apk/DichManHinh.apk`](apk/DichManHinh.apk) (GitHub Actions tự build sau mỗi lần push).

Yêu cầu Android 8.0 trở lên.

## Kiểm thử tự động

Workflow `E2E emulator` chạy trên emulator Android 14, 15, 16 với các kịch bản: dịch toàn màn hình,
dịch một ứng dụng, ép chia sẻ toàn màn hình, kiểu chữ tùy chỉnh, chọn vùng, ứng dụng chia sẻ bị ẩn,
màn hình cài đặt, đoạn văn tự xuống dòng, video học tiếng Trung (bảng từ vựng) và video tiếng Nga
(dựng lại từ ảnh chụp thực tế). Unit test (`./gradlew testDebugUnitTest`) kiểm tra ghép đoạn,
nhận diện chữ rác, định tuyến ngôn ngữ và chia lô yêu cầu dịch.

## Build thủ công

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```
