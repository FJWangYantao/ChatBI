export default function MosaicLogo({ size = 40 }: { size?: number }) {
  const blockSize = size / 4;

  return (
    <div
      className="grid grid-cols-4 gap-[2px]"
      style={{ width: size, height: size }}
    >
      {/* 第一行 */}
      <div className="bg-black dark:bg-white" />
      <div className="bg-black dark:bg-white" />
      <div className="bg-transparent" />
      <div className="bg-black dark:bg-white" />

      {/* 第二行 */}
      <div className="bg-black dark:bg-white" />
      <div className="bg-transparent" />
      <div className="bg-transparent" />
      <div className="bg-black dark:bg-white" />

      {/* 第三行 */}
      <div className="bg-black dark:bg-white" />
      <div className="bg-black dark:bg-white" />
      <div className="bg-transparent" />
      <div className="bg-black dark:bg-white" />

      {/* 第四行 */}
      <div className="bg-black dark:bg-white" />
      <div className="bg-transparent" />
      <div className="bg-transparent" />
      <div className="bg-black dark:bg-white" />
    </div>
  );
}
