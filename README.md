# Dịch Màn Hình (Screen Translator)

Ứng dụng Android dịch chữ trên màn hình (tương tự *Translate On Screen – EZ Screen Translator*).

- Nút nổi trên mọi ứng dụng: **chạm** để dịch toàn màn hình, **nhấn giữ** để chọn vùng cần dịch.
- Hỗ trợ cả hai chế độ chia sẻ của Android 14+: **Toàn bộ màn hình** và **Một ứng dụng**.
- Nhận dạng chữ (OCR) bằng ML Kit trên máy: Latin, Trung, Nhật, Hàn, Devanagari (tự động chọn).
- Dịch bằng Google Translate, hỗ trợ hơn 130 ngôn ngữ, có chế độ tự phát hiện ngôn ngữ nguồn.
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
màn hình cài đặt.

## Build thủ công

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```
