use super::*;
use codeana_contracts::{envelope, worker};
use codeana_model as m;
#[tokio::test]
async fn persistent_reader_resumes_prefix_and_body_after_cancelled_waits() {
    let expected = envelope(
        "fragmented",
        worker::envelope::Body::Cancel(worker::Cancel::default()),
    );
    let body = expected.encode_to_vec();
    let bytes = [
        (body.len() as u32).to_be_bytes().as_slice(),
        body.as_slice(),
    ]
    .concat();
    let (mut writer, mut input) = tokio::io::duplex(128);
    let mut reader = FrameReader::default();
    let mut offset = 0;
    for end in [1, 3, 4, 5, bytes.len() - 1] {
        writer.write_all(&bytes[offset..end]).await.unwrap();
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(1), reader.next(&mut input))
                .await
                .is_err()
        );
        offset = end;
    }
    writer.write_all(&bytes[offset..]).await.unwrap();
    assert_eq!(reader.next(&mut input).await.unwrap(), expected);
    write_frame(&mut writer, &expected).await.unwrap();
    assert_eq!(reader.next(&mut input).await.unwrap(), expected);
    drop(writer);
    assert!(
        reader
            .next(&mut input)
            .await
            .unwrap_err()
            .to_string()
            .contains("CONTROL_FRAME_EOF")
    );
}
#[tokio::test]
async fn oversized_frame_rejected_before_allocation() {
    let (mut a, mut b) = tokio::io::duplex(32);
    a.write_u32((m::MAX_FRAME + 1) as u32).await.unwrap();
    assert!(read_frame(&mut b).await.is_err());
}
#[tokio::test]
async fn excessive_advice_fields_rejected_before_decode() {
    let (mut writer, mut reader) = tokio::io::duplex(16384);
    let advice = worker::BuildAdvice {
        missing: vec![String::new(); 1001],
        ..Default::default()
    };
    write_frame(
        &mut writer,
        &envelope("attempt", worker::envelope::Body::Advice(advice)),
    )
    .await
    .unwrap();
    assert!(read_frame(&mut reader).await.is_err());
}
